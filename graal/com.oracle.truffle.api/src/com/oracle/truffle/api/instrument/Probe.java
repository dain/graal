/*
 * Copyright (c) 2014, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
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
package com.oracle.truffle.api.instrument;

import com.oracle.truffle.api.source.*;

/**
 * A collector of {@link ExecutionEvents} at a specific site (node) in a Truffle AST (generated by a
 * {@link Wrapper} inserted into the AST) for the purpose of <em>instrumentation</em>. For probes
 * associated with programmer-facing tools, there should be no more than one probe associated with a
 * particular piece of source code syntax (i.e. a {@link SourceSection}).
 * <p>
 * Any {@linkplain PhylumTag tags} associated with a particular piece of source code syntax are
 * managed by the probe.
 * <p>
 * When ASTs are copied, it is presumed that the probe for a site is shared by all AST nodes
 * representing that site.
 * <p>
 * A probe holds zero or more {@link Instrument}s, which can be added and removed dynamically.
 * Events reported to a probe are propagated to every attached instrument; the order is undefined.
 * <p>
 * Probe methods must be amenable to Truffle/Graal inlining on the assumption that the collection of
 * attached instruments seldom changes. The assumption is invalidated when instruments are added or
 * removed, but some instruments may change their internal state in such a way that the assumption
 * should also be invalidated.
 * <p>
 * <strong>Disclaimer:</strong> experimental interface under development. In particular, the
 * <em>notify</em> methods must be migrated to another interface.
 *
 * @see Instrument
 * @see Wrapper
 */
public interface Probe extends ExecutionEvents, PhylumTagged {

    /**
     * The source location with which this probe is (presumably uniquely) associated.
     */
    SourceSection getSourceLocation();

    /**
     * Mark this probe as being associated with an AST node in some category useful for debugging
     * and other tools.
     */
    void tagAs(PhylumTag tag);

    /**
     * Adds an instrument to this probe.
     */
    void addInstrument(Instrument newInstrument);

    /**
     * Removes an instrument from this probe.
     */
    void removeInstrument(Instrument oldInstrument);

}
