/*
 * Copyright (c) 2011, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.hotspot.debug;

import static com.oracle.graal.hotspot.HotSpotGraalRuntime.*;

import com.oracle.graal.api.meta.*;
import com.oracle.graal.hotspot.meta.*;

public class LocalImpl implements Local {

    private final String name;
    private final int startBci;
    private final int endBci;
    private final int slot;
    private final ResolvedJavaType resolvedType;

    public LocalImpl(String name, String type, HotSpotResolvedObjectType holder, int startBci, int endBci, int slot) {
        this.name = name;
        this.startBci = startBci;
        this.endBci = endBci;
        this.slot = slot;
        JavaType t = runtime().lookupType(type, holder, true);
        if (t instanceof ResolvedJavaType) {
            this.resolvedType = (ResolvedJavaType) runtime().lookupType(type, holder, false);
        } else {
            throw new AssertionError(t.getClass() + " is not a ResolvedJavaType");
        }
    }

    @Override
    public int getStartBCI() {
        return startBci;
    }

    @Override
    public int getEndBCI() {
        return endBci;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public ResolvedJavaType getType() {
        return resolvedType;
    }

    @Override
    public int getSlot() {
        return slot;
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof LocalImpl)) {
            return false;
        }
        LocalImpl that = (LocalImpl) obj;
        return this.name.equals(that.name) && this.startBci == that.startBci && this.endBci == that.endBci && this.slot == that.slot && this.resolvedType.equals(that.resolvedType);
    }

    @Override
    public int hashCode() {
        return super.hashCode();
    }

    @Override
    public String toString() {
        return "LocalImpl<name=" + name + ", resolvedType=" + resolvedType + ", startBci=" + startBci + ", endBci=" + endBci + ", slot=" + slot + ">";
    }
}
