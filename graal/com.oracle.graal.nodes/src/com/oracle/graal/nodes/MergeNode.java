/*
 * Copyright (c) 2009, 2011, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.nodes;

import static com.oracle.graal.graph.iterators.NodePredicates.*;

import java.util.*;

import com.oracle.graal.debug.*;
import com.oracle.graal.graph.*;
import com.oracle.graal.graph.iterators.*;
import com.oracle.graal.graph.spi.*;
import com.oracle.graal.nodes.spi.*;
import com.oracle.graal.nodes.util.*;

/**
 * Denotes the merging of multiple control-flow paths.
 */
public class MergeNode extends BeginStateSplitNode implements IterableNodeType, LIRLowerable {

    @Input(notDataflow = true) private final NodeInputList<AbstractEndNode> ends = new NodeInputList<>(this);

    @Override
    public void generate(LIRGeneratorTool gen) {
        gen.visitMerge(this);
    }

    public int forwardEndIndex(AbstractEndNode end) {
        return ends.indexOf(end);
    }

    public void addForwardEnd(AbstractEndNode end) {
        ends.add(end);
    }

    public int forwardEndCount() {
        return ends.size();
    }

    public AbstractEndNode forwardEndAt(int index) {
        return ends.get(index);
    }

    @Override
    public NodeIterable<AbstractEndNode> cfgPredecessors() {
        return ends;
    }

    /**
     * Determines if a given node is a phi whose {@linkplain PhiNode#merge() merge} is this node.
     * 
     * @param value the instruction to test
     * @return {@code true} if {@code value} is a phi and its merge is {@code this}
     */
    public boolean isPhiAtMerge(Node value) {
        return value instanceof PhiNode && ((PhiNode) value).merge() == this;
    }

    /**
     * Removes the given end from the merge, along with the entries corresponding to this end in the
     * phis connected to the merge.
     * 
     * @param pred the end to remove
     */
    public void removeEnd(AbstractEndNode pred) {
        int predIndex = phiPredecessorIndex(pred);
        assert predIndex != -1;
        deleteEnd(pred);
        for (PhiNode phi : phis().snapshot()) {
            if (phi.isDeleted()) {
                continue;
            }
            ValueNode removedValue = phi.valueAt(predIndex);
            phi.removeInput(predIndex);
            if (removedValue != null && removedValue.isAlive() && removedValue.recordsUsages() && removedValue.usages().isEmpty() && GraphUtil.isFloatingNode().apply(removedValue)) {
                GraphUtil.killWithUnusedFloatingInputs(removedValue);
            }
        }
    }

    protected void deleteEnd(AbstractEndNode end) {
        ends.remove(end);
    }

    public void clearEnds() {
        ends.clear();
    }

    public NodeIterable<AbstractEndNode> forwardEnds() {
        return ends;
    }

    public int phiPredecessorCount() {
        return forwardEndCount();
    }

    public int phiPredecessorIndex(AbstractEndNode pred) {
        return forwardEndIndex(pred);
    }

    public AbstractEndNode phiPredecessorAt(int index) {
        return forwardEndAt(index);
    }

    public NodeIterable<PhiNode> phis() {
        return this.usages().filter(PhiNode.class).filter(new NodePredicate() {

            @Override
            public boolean apply(Node n) {
                return ((PhiNode) n).merge() == MergeNode.this;
            }
        });
    }

    @Override
    public NodeIterable<Node> anchored() {
        return super.anchored().filter(isNotA(PhiNode.class).or(new NodePredicate() {

            @Override
            public boolean apply(Node n) {
                return ((PhiNode) n).merge() != MergeNode.this;
            }
        }));
    }

    @Override
    public void simplify(SimplifierTool tool) {
        FixedNode next = next();
        if (next instanceof AbstractEndNode) {
            AbstractEndNode origLoopEnd = (AbstractEndNode) next;
            MergeNode merge = origLoopEnd.merge();
            if (merge instanceof LoopBeginNode && !(origLoopEnd instanceof LoopEndNode)) {
                return;
            }
            // in order to move anchored values to the other merge we would need to check if the
            // anchors are used by phis of the other merge
            if (this.anchored().isNotEmpty()) {
                return;
            }
            if (merge.stateAfter() == null && this.stateAfter() != null) {
                // We hold a state, but the succeeding merge does not => do not combine.
                return;
            }
            for (PhiNode phi : phis()) {
                for (Node usage : phi.usages().filter(isNotA(FrameState.class))) {
                    if (!merge.isPhiAtMerge(usage)) {
                        return;
                    }
                }
            }
            Debug.log("Split %s into ends for %s.", this, merge);
            int numEnds = this.forwardEndCount();
            for (int i = 0; i < numEnds - 1; i++) {
                AbstractEndNode end = forwardEndAt(numEnds - 1 - i);
                AbstractEndNode newEnd;
                if (merge instanceof LoopBeginNode) {
                    newEnd = graph().add(new LoopEndNode((LoopBeginNode) merge));
                } else {
                    newEnd = graph().add(new EndNode());
                    merge.addForwardEnd(newEnd);
                }
                for (PhiNode phi : merge.phis()) {
                    ValueNode v = phi.valueAt(origLoopEnd);
                    ValueNode newInput;
                    if (isPhiAtMerge(v)) {
                        PhiNode endPhi = (PhiNode) v;
                        newInput = endPhi.valueAt(end);
                    } else {
                        newInput = v;
                    }
                    phi.addInput(newInput);
                }
                this.removeEnd(end);
                end.replaceAtPredecessor(newEnd);
                end.safeDelete();
                tool.addToWorkList(newEnd.predecessor()); // ?
            }
            graph().reduceTrivialMerge(this);
        } else if (next instanceof ReturnNode) {
            ReturnNode returnNode = (ReturnNode) next;
            if (anchored().isNotEmpty() || returnNode.getMemoryMap() != null) {
                return;
            }
            List<PhiNode> phis = phis().snapshot();
            for (PhiNode phi : phis) {
                for (Node usage : phi.usages().filter(isNotA(FrameState.class))) {
                    if (usage != returnNode) {
                        return;
                    }
                }
            }

            PhiNode returnValuePhi = returnNode.result() == null || !isPhiAtMerge(returnNode.result()) ? null : (PhiNode) returnNode.result();
            List<AbstractEndNode> endNodes = forwardEnds().snapshot();
            for (AbstractEndNode end : endNodes) {
                ReturnNode newReturn = graph().add(new ReturnNode(returnValuePhi == null ? returnNode.result() : returnValuePhi.valueAt(end)));
                end.replaceAtPredecessor(newReturn);
            }
            GraphUtil.killCFG(this);
            for (AbstractEndNode end : endNodes) {
                end.safeDelete();
            }
            for (PhiNode phi : phis) {
                if (phi.isAlive() && phi.usages().isEmpty()) {
                    GraphUtil.killWithUnusedFloatingInputs(phi);
                }
            }
        }
    }
}
