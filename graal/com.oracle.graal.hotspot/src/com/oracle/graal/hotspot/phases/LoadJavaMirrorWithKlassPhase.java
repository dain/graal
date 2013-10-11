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
package com.oracle.graal.hotspot.phases;

import static com.oracle.graal.api.meta.LocationIdentity.*;

import com.oracle.graal.api.meta.*;
import com.oracle.graal.hotspot.meta.*;
import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.extended.*;
import com.oracle.graal.nodes.type.*;
import com.oracle.graal.phases.*;
import com.oracle.graal.phases.common.*;
import com.oracle.graal.phases.tiers.*;

/**
 * For AOT compilation we aren't allowed to use a {@link Class} reference ({@code javaMirror})
 * directly. Instead the {@link Class} reference should be obtained from the {@code Klass} object.
 * The reason for this is, that in Class Data Sharing (CDS) a {@code Klass} object is mapped to a
 * fixed address in memory, but the {@code javaMirror} is not (which lives in the Java heap).
 * 
 * Lowering can introduce new {@link ConstantNode}s containing a {@link Class} reference, thus this
 * phase must be applied after {@link LoweringPhase}.
 * 
 * @see AheadOfTimeVerificationPhase
 */
public class LoadJavaMirrorWithKlassPhase extends BasePhase<PhaseContext> {

    @Override
    protected void run(StructuredGraph graph, PhaseContext context) {
        for (ConstantNode node : graph.getNodes().filter(ConstantNode.class)) {
            Constant constant = node.asConstant();
            if (constant.getKind() == Kind.Object && constant.asObject() instanceof Class<?>) {
                ResolvedJavaType type = context.getMetaAccess().lookupJavaType((Class<?>) constant.asObject());
                assert type instanceof HotSpotResolvedObjectType;

                HotSpotRuntime runtime = (HotSpotRuntime) context.getMetaAccess();

                Constant klass = ((HotSpotResolvedObjectType) type).klass();
                ConstantNode klassNode = ConstantNode.forConstant(klass, runtime, graph);

                Stamp stamp = StampFactory.exactNonNull(runtime.lookupJavaType(Class.class));
                LocationNode location = graph.unique(ConstantLocationNode.create(FINAL_LOCATION, stamp.kind(), runtime.config.classMirrorOffset, graph));
                FloatingReadNode freadNode = graph.unique(new FloatingReadNode(klassNode, location, null, stamp));

                graph.replaceFloating(node, freadNode);
            }
        }
    }
}
