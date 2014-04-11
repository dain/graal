/*
 * Copyright (c) 2013, Oracle and/or its affiliates. All rights reserved.
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
 */
package com.oracle.graal.hotspot.meta;

import static com.oracle.graal.api.code.CallingConvention.Type.*;
import static com.oracle.graal.api.meta.LocationIdentity.*;
import static com.oracle.graal.hotspot.HotSpotBackend.*;
import static com.oracle.graal.hotspot.HotSpotForeignCallLinkage.RegisterEffect.*;
import static com.oracle.graal.hotspot.HotSpotForeignCallLinkage.Transition.*;
import static com.oracle.graal.hotspot.nodes.NewArrayStubCall.*;
import static com.oracle.graal.hotspot.nodes.NewInstanceStubCall.*;
import static com.oracle.graal.hotspot.nodes.NewMultiArrayStubCall.*;
import static com.oracle.graal.hotspot.nodes.UncommonTrapCallNode.*;
import static com.oracle.graal.hotspot.nodes.VMErrorNode.*;
import static com.oracle.graal.hotspot.replacements.HotSpotReplacementsUtil.*;
import static com.oracle.graal.hotspot.replacements.MonitorSnippets.*;
import static com.oracle.graal.hotspot.replacements.NewObjectSnippets.*;
import static com.oracle.graal.hotspot.replacements.SystemSubstitutions.*;
import static com.oracle.graal.hotspot.replacements.ThreadSubstitutions.*;
import static com.oracle.graal.hotspot.replacements.WriteBarrierSnippets.*;
import static com.oracle.graal.hotspot.stubs.DeoptimizationStub.*;
import static com.oracle.graal.hotspot.stubs.ExceptionHandlerStub.*;
import static com.oracle.graal.hotspot.stubs.NewArrayStub.*;
import static com.oracle.graal.hotspot.stubs.NewInstanceStub.*;
import static com.oracle.graal.hotspot.stubs.StubUtil.*;
import static com.oracle.graal.hotspot.stubs.UnwindExceptionToCallerStub.*;
import static com.oracle.graal.hotspot.meta.HotSpotLoweringProvider.RuntimeCalls.*;
import static com.oracle.graal.nodes.java.RegisterFinalizerNode.*;
import static com.oracle.graal.replacements.Log.*;
import static com.oracle.graal.replacements.MathSubstitutionsX86.*;

import java.util.*;

import com.oracle.graal.api.code.*;
import com.oracle.graal.api.meta.*;
import com.oracle.graal.hotspot.*;
import com.oracle.graal.hotspot.stubs.*;
import com.oracle.graal.word.*;

/**
 * HotSpot implementation of {@link ForeignCallsProvider}.
 */
public abstract class HotSpotHostForeignCallsProvider extends HotSpotForeignCallsProviderImpl {

    public HotSpotHostForeignCallsProvider(HotSpotGraalRuntime runtime, MetaAccessProvider metaAccess, CodeCacheProvider codeCache) {
        super(runtime, metaAccess, codeCache);
    }

    protected static void link(Stub stub) {
        stub.getLinkage().setCompiledStub(stub);
    }

    public static ForeignCallDescriptor lookupArraycopyDescriptor(Kind kind, boolean aligned, boolean disjoint) {
        return arraycopyDescriptors[aligned ? 1 : 0][disjoint ? 1 : 0].get(kind);
    }

    @SuppressWarnings("unchecked") private static final EnumMap<Kind, ForeignCallDescriptor>[][] arraycopyDescriptors = new EnumMap[2][2];

    static {
        // Populate the EnumMap instances
        for (int i = 0; i < arraycopyDescriptors.length; i++) {
            for (int j = 0; j < arraycopyDescriptors[i].length; j++) {
                arraycopyDescriptors[i][j] = new EnumMap<>(Kind.class);
            }
        }
    }

    private static ForeignCallDescriptor registerArraycopyDescriptor(Kind kind, boolean aligned, boolean disjoint) {
        String name = kind + (aligned ? "Aligned" : "") + (disjoint ? "Disjoint" : "") + "Arraycopy";
        ForeignCallDescriptor desc = new ForeignCallDescriptor(name, void.class, Word.class, Word.class, Word.class);
        arraycopyDescriptors[aligned ? 1 : 0][disjoint ? 1 : 0].put(kind, desc);
        return desc;
    }

    private void registerArrayCopy(Kind kind, long routine, long alignedRoutine, long disjointRoutine, long alignedDisjointRoutine) {
        LocationIdentity killed = NamedLocationIdentity.getArrayLocation(kind);
        registerForeignCall(registerArraycopyDescriptor(kind, false, false), routine, NativeCall, DESTROYS_REGISTERS, LEAF_NOFP, NOT_REEXECUTABLE, killed);
        registerForeignCall(registerArraycopyDescriptor(kind, true, false), alignedRoutine, NativeCall, DESTROYS_REGISTERS, LEAF_NOFP, NOT_REEXECUTABLE, killed);
        registerForeignCall(registerArraycopyDescriptor(kind, false, true), disjointRoutine, NativeCall, DESTROYS_REGISTERS, LEAF_NOFP, NOT_REEXECUTABLE, killed);
        registerForeignCall(registerArraycopyDescriptor(kind, true, true), alignedDisjointRoutine, NativeCall, DESTROYS_REGISTERS, LEAF_NOFP, NOT_REEXECUTABLE, killed);
    }

    public void initialize(HotSpotProviders providers, HotSpotVMConfig c) {
        TargetDescription target = providers.getCodeCache().getTarget();

        registerForeignCall(DEOPT_HANDLER, c.handleDeoptStub, NativeCall, PRESERVES_REGISTERS, LEAF_NOFP, REEXECUTABLE, NO_LOCATIONS);
        registerForeignCall(IC_MISS_HANDLER, c.inlineCacheMissStub(), NativeCall, PRESERVES_REGISTERS, LEAF_NOFP, REEXECUTABLE, NO_LOCATIONS);

        registerForeignCall(JAVA_TIME_MILLIS, c.javaTimeMillisAddress, NativeCall, DESTROYS_REGISTERS, LEAF_NOFP, REEXECUTABLE, NO_LOCATIONS);
        registerForeignCall(JAVA_TIME_NANOS, c.javaTimeNanosAddress, NativeCall, DESTROYS_REGISTERS, LEAF_NOFP, REEXECUTABLE, NO_LOCATIONS);
        registerForeignCall(ARITHMETIC_SIN, c.arithmeticSinAddress, NativeCall, DESTROYS_REGISTERS, LEAF, REEXECUTABLE, NO_LOCATIONS);
        registerForeignCall(ARITHMETIC_COS, c.arithmeticCosAddress, NativeCall, DESTROYS_REGISTERS, LEAF, REEXECUTABLE, NO_LOCATIONS);
        registerForeignCall(ARITHMETIC_TAN, c.arithmeticTanAddress, NativeCall, DESTROYS_REGISTERS, LEAF, REEXECUTABLE, NO_LOCATIONS);
        registerForeignCall(LOAD_AND_CLEAR_EXCEPTION, c.loadAndClearExceptionAddress, NativeCall, DESTROYS_REGISTERS, LEAF_NOFP, NOT_REEXECUTABLE, ANY_LOCATION);

        registerForeignCall(EXCEPTION_HANDLER_FOR_PC, c.exceptionHandlerForPcAddress, NativeCall, DESTROYS_REGISTERS, NOT_LEAF, REEXECUTABLE, ANY_LOCATION);
        registerForeignCall(EXCEPTION_HANDLER_FOR_RETURN_ADDRESS, c.exceptionHandlerForReturnAddressAddress, NativeCall, DESTROYS_REGISTERS, NOT_LEAF, REEXECUTABLE, ANY_LOCATION);
        registerForeignCall(FETCH_UNROLL_INFO, c.deoptimizationFetchUnrollInfo, NativeCall, DESTROYS_REGISTERS, NOT_LEAF, REEXECUTABLE, ANY_LOCATION);
        registerForeignCall(NEW_ARRAY_C, c.newArrayAddress, NativeCall, DESTROYS_REGISTERS, NOT_LEAF, REEXECUTABLE, ANY_LOCATION);
        registerForeignCall(NEW_INSTANCE_C, c.newInstanceAddress, NativeCall, DESTROYS_REGISTERS, NOT_LEAF, REEXECUTABLE, ANY_LOCATION);
        registerForeignCall(UNCOMMON_TRAP, c.deoptimizationUncommonTrap, NativeCall, DESTROYS_REGISTERS, NOT_LEAF, NOT_REEXECUTABLE, ANY_LOCATION);
        registerForeignCall(UNPACK_FRAMES, c.deoptimizationUnpackFrames, NativeCall, DESTROYS_REGISTERS, LEAF, NOT_REEXECUTABLE, ANY_LOCATION);
        registerForeignCall(VM_MESSAGE_C, c.vmMessageAddress, NativeCall, DESTROYS_REGISTERS, NOT_LEAF, REEXECUTABLE, NO_LOCATIONS);

        link(new NewInstanceStub(providers, target, registerStubCall(NEW_INSTANCE, REEXECUTABLE, NOT_LEAF, ANY_LOCATION)));
        link(new NewArrayStub(providers, target, registerStubCall(NEW_ARRAY, REEXECUTABLE, NOT_LEAF, INIT_LOCATION)));
        link(new ExceptionHandlerStub(providers, target, foreignCalls.get(EXCEPTION_HANDLER)));
        link(new UnwindExceptionToCallerStub(providers, target, registerStubCall(UNWIND_EXCEPTION_TO_CALLER, NOT_REEXECUTABLE, NOT_LEAF, ANY_LOCATION)));
        link(new VerifyOopStub(providers, target, registerStubCall(VERIFY_OOP, REEXECUTABLE, LEAF_NOFP, NO_LOCATIONS)));

        linkForeignCall(providers, IDENTITY_HASHCODE, c.identityHashCodeAddress, PREPEND_THREAD, NOT_LEAF, NOT_REEXECUTABLE, MARK_WORD_LOCATION);
        linkForeignCall(providers, REGISTER_FINALIZER, c.registerFinalizerAddress, PREPEND_THREAD, NOT_LEAF, NOT_REEXECUTABLE, ANY_LOCATION);
        linkForeignCall(providers, CREATE_NULL_POINTER_EXCEPTION, c.createNullPointerExceptionAddress, PREPEND_THREAD, NOT_LEAF, REEXECUTABLE, ANY_LOCATION);
        linkForeignCall(providers, CREATE_OUT_OF_BOUNDS_EXCEPTION, c.createOutOfBoundsExceptionAddress, PREPEND_THREAD, NOT_LEAF, REEXECUTABLE, ANY_LOCATION);
        linkForeignCall(providers, MONITORENTER, c.monitorenterAddress, PREPEND_THREAD, NOT_LEAF, NOT_REEXECUTABLE, ANY_LOCATION);
        linkForeignCall(providers, MONITOREXIT, c.monitorexitAddress, PREPEND_THREAD, LEAF_SP, NOT_REEXECUTABLE, ANY_LOCATION);
        linkForeignCall(providers, NEW_MULTI_ARRAY, c.newMultiArrayAddress, PREPEND_THREAD, NOT_LEAF, REEXECUTABLE, INIT_LOCATION);
        linkForeignCall(providers, DYNAMIC_NEW_ARRAY, c.dynamicNewArrayAddress, PREPEND_THREAD, NOT_LEAF, REEXECUTABLE, INIT_LOCATION);
        linkForeignCall(providers, DYNAMIC_NEW_INSTANCE, c.dynamicNewInstanceAddress, PREPEND_THREAD, NOT_LEAF, REEXECUTABLE, INIT_LOCATION);
        linkForeignCall(providers, LOG_PRINTF, c.logPrintfAddress, PREPEND_THREAD, LEAF, REEXECUTABLE, NO_LOCATIONS);
        linkForeignCall(providers, LOG_OBJECT, c.logObjectAddress, PREPEND_THREAD, NOT_LEAF, REEXECUTABLE, NO_LOCATIONS);
        linkForeignCall(providers, LOG_PRIMITIVE, c.logPrimitiveAddress, PREPEND_THREAD, NOT_LEAF, REEXECUTABLE, NO_LOCATIONS);
        linkForeignCall(providers, THREAD_IS_INTERRUPTED, c.threadIsInterruptedAddress, PREPEND_THREAD, NOT_LEAF, NOT_REEXECUTABLE, ANY_LOCATION);
        linkForeignCall(providers, VM_ERROR, c.vmErrorAddress, PREPEND_THREAD, LEAF_NOFP, REEXECUTABLE, NO_LOCATIONS);
        linkForeignCall(providers, OSR_MIGRATION_END, c.osrMigrationEndAddress, DONT_PREPEND_THREAD, LEAF_NOFP, NOT_REEXECUTABLE, NO_LOCATIONS);
        linkForeignCall(providers, G1WBPRECALL, c.writeBarrierPreAddress, PREPEND_THREAD, LEAF_NOFP, REEXECUTABLE, NO_LOCATIONS);
        linkForeignCall(providers, G1WBPOSTCALL, c.writeBarrierPostAddress, PREPEND_THREAD, LEAF_NOFP, REEXECUTABLE, NO_LOCATIONS);
        linkForeignCall(providers, VALIDATE_OBJECT, c.validateObject, PREPEND_THREAD, LEAF_NOFP, REEXECUTABLE, NO_LOCATIONS);

        registerArrayCopy(Kind.Byte, c.jbyteArraycopy, c.jbyteAlignedArraycopy, c.jbyteDisjointArraycopy, c.jbyteAlignedDisjointArraycopy);
        registerArrayCopy(Kind.Boolean, c.jbyteArraycopy, c.jbyteAlignedArraycopy, c.jbyteDisjointArraycopy, c.jbyteAlignedDisjointArraycopy);
        registerArrayCopy(Kind.Char, c.jshortArraycopy, c.jshortAlignedArraycopy, c.jshortDisjointArraycopy, c.jshortAlignedDisjointArraycopy);
        registerArrayCopy(Kind.Short, c.jshortArraycopy, c.jshortAlignedArraycopy, c.jshortDisjointArraycopy, c.jshortAlignedDisjointArraycopy);
        registerArrayCopy(Kind.Int, c.jintArraycopy, c.jintAlignedArraycopy, c.jintDisjointArraycopy, c.jintAlignedDisjointArraycopy);
        registerArrayCopy(Kind.Float, c.jintArraycopy, c.jintAlignedArraycopy, c.jintDisjointArraycopy, c.jintAlignedDisjointArraycopy);
        registerArrayCopy(Kind.Long, c.jlongArraycopy, c.jlongAlignedArraycopy, c.jlongDisjointArraycopy, c.jlongAlignedDisjointArraycopy);
        registerArrayCopy(Kind.Double, c.jlongArraycopy, c.jlongAlignedArraycopy, c.jlongDisjointArraycopy, c.jlongAlignedDisjointArraycopy);
        registerArrayCopy(Kind.Object, c.oopArraycopy, c.oopArraycopy, c.oopDisjointArraycopy, c.oopDisjointArraycopy);
    }
}
