/*
 * Copyright (c) 2014, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 *
 */

#ifndef GPU_HSAIL_VM_GPU_HSAIL_TLAB_HPP
#define GPU_HSAIL_VM_GPU_HSAIL_TLAB_HPP

#include "graal/graalEnv.hpp"
#include "code/debugInfo.hpp"
#include "code/location.hpp"
#include "gpu_hsail.hpp"

class HSAILAllocationInfo;

class HSAILTlabInfo VALUE_OBJ_CLASS_SPEC {
  friend class VMStructs;
public:
  // uses only the necessary fields from a full TLAB
  HeapWord* _start;
  HeapWord* _top;
  HeapWord* _end;
  HeapWord* _last_good_top;
  HeapWord* _original_top;
  JavaThread* _donor_thread;         // donor thread associated with this tlabInfo
  HSAILAllocationInfo* _alloc_info;   // same as what is in HSAILDeoptimizationInfo

  // Accessors
  HeapWord* start() { return _start; }
  HeapWord* top() { return _top; }
  HeapWord* end() { return _end; }
  HeapWord* last_good_top() { return _last_good_top; }
  HeapWord* original_top() { return _original_top; }
  void initialize(HeapWord* start, HeapWord* top, HeapWord* end, JavaThread* donorThread, HSAILAllocationInfo* allocInfo) {
    _start = start;
    _top = _original_top = top;
    _end = end;
    _donor_thread = donorThread;
    _alloc_info = allocInfo;
  }
};


class HSAILAllocationInfo : public CHeapObj<mtInternal> {
  friend class VMStructs;
private:
  JavaThread** donorThreads;
  jint _num_donor_threads;
  size_t _tlab_align_reserve_bytes;    // filled in from ThreadLocalAllocBuffer::alignment_reserve_in_bytes()
  HSAILTlabInfo** _cur_tlab_infos;    // array of current tlab info pointers, one per donor_thread
  HSAILTlabInfo* _tlab_infos_pool_start;    // pool for new tlab_infos
  HSAILTlabInfo* _tlab_infos_pool_next;     // where next will be allocated from
  HSAILTlabInfo* _tlab_infos_pool_end;      // where next will be allocated from

public:
  HSAILAllocationInfo(jobject donor_threads_jobj, int dimX, int allocBytesPerWorkitem) {
    // fill in the donorThreads array
    objArrayOop donorThreadObjects = (objArrayOop) JNIHandles::resolve(donor_threads_jobj);
    _num_donor_threads = donorThreadObjects->length();
    guarantee(_num_donor_threads > 0, "need at least one donor thread");
    donorThreads = NEW_C_HEAP_ARRAY(JavaThread*, _num_donor_threads, mtInternal);
    for (int i = 0; i < _num_donor_threads; i++) {
      donorThreads[i] = java_lang_Thread::thread(donorThreadObjects->obj_at(i));
    }
    
    // Compute max_tlab_infos based on amount of free heap space
    size_t max_tlab_infos;
    {
      JavaThread* donorThread = donorThreads[0];
      ThreadLocalAllocBuffer* tlab = &donorThread->tlab();
      size_t new_tlab_size = tlab->compute_size(0);
      size_t heap_bytes_free = Universe::heap()->unsafe_max_tlab_alloc(donorThread);
      if (new_tlab_size != 0) {
        max_tlab_infos = MIN2(heap_bytes_free / new_tlab_size, (size_t)(64 * _num_donor_threads));
      } else {
        max_tlab_infos = 8 * _num_donor_threads;   // an arbitrary multiple
      }
      if (TraceGPUInteraction) {
        tty->print_cr("heapFree = %ld, newTlabSize=%ld, tlabInfos allocated = %ld", heap_bytes_free, new_tlab_size, max_tlab_infos);
      }
    }

    _cur_tlab_infos = NEW_C_HEAP_ARRAY(HSAILTlabInfo*, _num_donor_threads, mtInternal);
    _tlab_infos_pool_start = NEW_C_HEAP_ARRAY(HSAILTlabInfo, max_tlab_infos, mtInternal);
    _tlab_infos_pool_next = &_tlab_infos_pool_start[_num_donor_threads];
    _tlab_infos_pool_end = &_tlab_infos_pool_start[max_tlab_infos];
    _tlab_align_reserve_bytes = ThreadLocalAllocBuffer::alignment_reserve_in_bytes();
      
    // we will fill the first N tlabInfos from the donor threads
    for (int i = 0; i < _num_donor_threads; i++) {
      JavaThread* donorThread = donorThreads[i];
      ThreadLocalAllocBuffer* tlab = &donorThread->tlab();
      if (TraceGPUInteraction) {
        tty->print("donorThread %d, is %p, tlab at %p -> ", i, donorThread, tlab);
        printTlabInfoFromThread(tlab);
      }
      
      // Here we try to get a new tlab if current one is null. Note:
      // eventually we may want to test if the size is too small based
      // on some heuristic where we see how much this kernel tends to
      // allocate, but for now we can just let it overflow and let the
      // GPU allocate new tlabs. Actually, if we can't prime a tlab
      // here, it might make sense to do a gc now rather than to start
      // the kernel and have it deoptimize.  How to do that?
      if (tlab->end() == NULL) {
        bool success = getNewTlabForDonorThread(tlab, i);
        if (TraceGPUInteraction) {
          if (success) {
            tty->print("donorThread %d, refilled tlab, -> ", i);
            printTlabInfoFromThread(tlab);
          } else {
            tty->print("donorThread %d, could not refill tlab, left as ", i);
            printTlabInfoFromThread(tlab);
          }
        }
      }

      // extract the necessary tlab fields into a TlabInfo record
      HSAILTlabInfo* pTlabInfo = &_tlab_infos_pool_start[i];
      _cur_tlab_infos[i] = pTlabInfo;
      pTlabInfo->initialize(tlab->start(), tlab->top(), tlab->end(), donorThread, this);
    }
  }

  ~HSAILAllocationInfo() {
    FREE_C_HEAP_ARRAY(HSAILTlabInfo*, _cur_tlab_infos, mtInternal);
    FREE_C_HEAP_ARRAY(HSAILTlabInfo, _tlab_infos_pool_start, mtInternal);
    FREE_C_HEAP_ARRAY(JavaThread*, donorThreads, mtInternal);
  }

  void postKernelCleanup() {
    // go thru all the tlabInfos, fix up any tlab tops that overflowed
    // complete the tlabs if they overflowed
    // update the donor threads tlabs when appropriate
    bool anyOverflows = false;
    size_t bytesAllocated = 0;
    // if there was an overflow in allocating tlabInfos, correct it here
    if (_tlab_infos_pool_next > _tlab_infos_pool_end) {
      if (TraceGPUInteraction) {
        int overflowAmount = _tlab_infos_pool_next - _tlab_infos_pool_end;
        tty->print_cr("tlabInfo allocation overflowed by %d units", overflowAmount);
      }
      _tlab_infos_pool_next = _tlab_infos_pool_end;
    }
    for (HSAILTlabInfo* tlabInfo = _tlab_infos_pool_start; tlabInfo < _tlab_infos_pool_next; tlabInfo++) {
      if (TraceGPUInteraction) {
        tty->print_cr("postprocess tlabInfo %p, start=%p, top=%p, end=%p, last_good_top=%p", tlabInfo, 
                      tlabInfo->start(), tlabInfo->top(), tlabInfo->end(), tlabInfo->last_good_top());
      }
      JavaThread* donorThread = tlabInfo->_donor_thread;
      ThreadLocalAllocBuffer* tlab = &donorThread->tlab();
      bool overflowed = false;
      // if a tlabInfo has NULL fields, i.e. we could not prime it on entry,
      // or we could not get a tlab from the gpu, so ignore tlabInfo here
      if (tlabInfo->start() != NULL) {
        if (tlabInfo->top() > tlabInfo->end()) {
          anyOverflows = true;
          overflowed = true;
          if (TraceGPUInteraction) {
            long overflowAmount = (long) tlabInfo->top() - (long) tlabInfo->last_good_top(); 
            tty->print_cr("tlabInfo %p (donorThread = %p) overflowed by %ld bytes, setting last good top to %p", tlabInfo, donorThread, overflowAmount, tlabInfo->last_good_top());
          }
          tlabInfo->_top = tlabInfo->last_good_top();
        }

        // fill the donor thread tlab with the tlabInfo information
        // we do this even if it will get overwritten by a later tlabinfo
        // because it helps with tlab statistics for that donor thread
        tlab->fill(tlabInfo->start(), tlabInfo->top(), (tlabInfo->end() - tlabInfo->start()) + tlab->alignment_reserve());

        // if there was an overflow, make it parsable with retire = true
        if (overflowed) {
          tlab->make_parsable(true);
        }
        
        size_t delta = (long)(tlabInfo->top()) - (long)(tlabInfo->original_top());
        if (TraceGPUInteraction) {
          tty->print_cr("%ld bytes were allocated by tlabInfo %p (start %p, top %p, end %p", delta, tlabInfo,
                        tlabInfo->start(), tlabInfo->top(), tlabInfo->end());
        }
        bytesAllocated += delta;
      }
    }
    if (TraceGPUInteraction) {
      tty->print_cr("%ld total bytes were allocated in this kernel", bytesAllocated);
    }
    if (anyOverflows) {
      // Hsail::kernelStats.incOverflows();
    }
  }

  HSAILTlabInfo** getCurTlabInfos() {
    return _cur_tlab_infos;
  }

private:
  // fill and retire old tlab and get a new one
  // if we can't get one, no problem someone will eventually do a gc
  bool getNewTlabForDonorThread(ThreadLocalAllocBuffer* tlab, int idx) {

    tlab->clear_before_allocation();    // fill and retire old tlab (will also check for null)
    
    // get a size for a new tlab that is based on the desired_size
    size_t new_tlab_size = tlab->compute_size(0);
    if (new_tlab_size == 0) return false;
    
    HeapWord* tlab_start = Universe::heap()->allocate_new_tlab(new_tlab_size);
    if (tlab_start == NULL) return false;
    
    // ..and clear it if required
    if (ZeroTLAB) {
      Copy::zero_to_words(tlab_start, new_tlab_size);
    }
    // and init the tlab pointers
    tlab->fill(tlab_start, tlab_start, new_tlab_size);
    return true;
  }
  
  void printTlabInfoFromThread (ThreadLocalAllocBuffer* tlab) {
    HeapWord* start = tlab->start();
    HeapWord* top = tlab->top();
    HeapWord* end = tlab->end();
    // sizes are in bytes
    size_t tlabFree = tlab->free() * HeapWordSize;
    size_t tlabUsed = tlab->used() * HeapWordSize;
    size_t tlabSize = tlabFree + tlabUsed;
    double freePct = 100.0 * (double) tlabFree/(double) tlabSize;
    tty->print_cr("(%p, %p, %p), siz=%ld, free=%ld (%f%%)", start, top, end, tlabSize, tlabFree, freePct);
  }
  
};
  
#endif // GPU_HSAIL_VM_GPU_HSAIL_TLAB_HPP
