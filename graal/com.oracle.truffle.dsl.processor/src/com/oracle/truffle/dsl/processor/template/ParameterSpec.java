/*
 * Copyright (c) 2012, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.truffle.dsl.processor.template;

import java.util.*;

import javax.lang.model.type.*;

import com.oracle.truffle.dsl.processor.*;
import com.oracle.truffle.dsl.processor.node.*;
import com.oracle.truffle.dsl.processor.template.MethodSpec.TypeDef;

public class ParameterSpec {

    private final String name;
    private final List<TypeMirror> allowedTypes;

    /** Type is bound to local final variable. */
    private boolean local;
    private boolean signature;

    /** Optional bound execution of node. */
    private NodeExecutionData execution;
    private TypeDef typeDefinition;

    public ParameterSpec(String name, List<TypeMirror> allowedTypes) {
        this.name = name;
        this.allowedTypes = allowedTypes;
    }

    public ParameterSpec(String name, TypeMirror type) {
        this(name, Arrays.asList(type));
    }

    public ParameterSpec(ParameterSpec o, List<TypeMirror> allowedTypes) {
        this.name = o.name;
        this.local = o.local;
        this.typeDefinition = o.typeDefinition;
        this.execution = o.execution;
        this.signature = o.signature;
        this.allowedTypes = allowedTypes;
    }

    public NodeExecutionData getExecution() {
        return execution;
    }

    public void setExecution(NodeExecutionData executionData) {
        this.execution = executionData;
        this.signature = execution != null;
    }

    public void setSignature(boolean signature) {
        this.signature = signature;
    }

    void setTypeDefinition(TypeDef typeDefinition) {
        this.typeDefinition = typeDefinition;
    }

    TypeDef getTypeDefinition() {
        return typeDefinition;
    }

    public void setLocal(boolean local) {
        this.local = local;
    }

    public boolean isSignature() {
        return signature;
    }

    public boolean isLocal() {
        return local;
    }

    public String getName() {
        return name;
    }

    public List<TypeMirror> getAllowedTypes() {
        return allowedTypes;
    }

    public boolean matches(TypeMirror actualType) {
        for (TypeMirror mirror : allowedTypes) {
            if (Utils.typeEquals(actualType, mirror)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String toString() {
        return toSignatureString(false);
    }

    public String toSignatureString(boolean typeOnly) {
        StringBuilder builder = new StringBuilder();
        if (typeDefinition != null) {
            builder.append("<" + typeDefinition.getName() + ">");
        } else if (getAllowedTypes().size() >= 1) {
            builder.append(Utils.getSimpleName(getAllowedTypes().get(0)));
        } else {
            builder.append("void");
        }
        if (!typeOnly) {
            builder.append(" ");
            builder.append(getName());
        }
        return builder.toString();
    }

}
