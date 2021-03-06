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
package com.oracle.graal.truffle;

import static com.oracle.graal.truffle.OptimizedCallTargetLog.*;
import static com.oracle.graal.truffle.TruffleCompilerOptions.*;

import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.*;

import com.oracle.graal.api.code.*;
import com.oracle.graal.debug.*;
import com.oracle.truffle.api.*;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.frame.*;
import com.oracle.truffle.api.nodes.*;

/**
 * Call target that is optimized by Graal upon surpassing a specific invocation threshold.
 */
public class OptimizedCallTarget extends InstalledCode implements RootCallTarget, LoopCountReceiver, ReplaceObserver {

    protected static final PrintStream OUT = TTY.out().out();

    protected final GraalTruffleRuntime runtime;
    private SpeculationLog speculationLog;
    protected int callCount;
    protected boolean inliningPerformed;
    protected final CompilationProfile compilationProfile;
    protected final CompilationPolicy compilationPolicy;
    private OptimizedCallTarget splitSource;
    private final AtomicInteger callSitesKnown = new AtomicInteger(0);
    @CompilationFinal private Class<?>[] profiledArgumentTypes;
    @CompilationFinal private Assumption profiledArgumentTypesAssumption;
    @CompilationFinal private Class<?> profiledReturnType;
    @CompilationFinal private Assumption profiledReturnTypeAssumption;

    private final RootNode rootNode;

    private final Map<TruffleStamp, OptimizedCallTarget> splitVersions = new HashMap<>();
    private TruffleStamp argumentStamp = DefaultTruffleStamp.getInstance();

    public final RootNode getRootNode() {
        return rootNode;
    }

    public OptimizedCallTarget(RootNode rootNode, GraalTruffleRuntime runtime, int invokeCounter, int compilationThreshold, CompilationPolicy compilationPolicy, SpeculationLog speculationLog) {
        super(rootNode.toString());
        this.runtime = runtime;
        this.speculationLog = speculationLog;
        this.rootNode = rootNode;
        this.rootNode.adoptChildren();
        this.rootNode.setCallTarget(this);
        this.compilationPolicy = compilationPolicy;
        this.compilationProfile = new CompilationProfile(compilationThreshold, invokeCounter);
        if (TruffleCallTargetProfiling.getValue()) {
            registerCallTarget(this);
        }
    }

    public final void mergeArgumentStamp(TruffleStamp p) {
        this.argumentStamp = this.argumentStamp.join(p);
    }

    public final TruffleStamp getArgumentStamp() {
        return argumentStamp;
    }

    private int splitIndex;

    public int getSplitIndex() {
        return splitIndex;
    }

    public OptimizedCallTarget split() {
        if (!getRootNode().isSplittable()) {
            return null;
        }
        OptimizedCallTarget splitTarget = (OptimizedCallTarget) Truffle.getRuntime().createCallTarget(getRootNode().split());
        splitTarget.splitSource = this;
        splitTarget.splitIndex = splitIndex++;
        return splitTarget;
    }

    public Map<TruffleStamp, OptimizedCallTarget> getSplitVersions() {
        return splitVersions;
    }

    public SpeculationLog getSpeculationLog() {
        return speculationLog;
    }

    @Override
    public Object call(Object... args) {
        if (profiledArgumentTypesAssumption != null && profiledArgumentTypesAssumption.isValid()) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            profiledArgumentTypesAssumption.invalidate();
            profiledArgumentTypes = null;
        }
        return callBoundary(args);
    }

    public Object callDirect(Object... args) {
        profileArguments(args);
        Object result = callBoundary(args);
        Class<?> klass = profiledReturnType;
        if (klass != null && CompilerDirectives.inCompiledCode() && profiledReturnTypeAssumption.isValid()) {
            result = CompilerDirectives.unsafeCast(result, klass, true, true);
        }
        return result;
    }

    @ExplodeLoop
    private void profileArguments(Object[] args) {
        if (profiledArgumentTypesAssumption == null) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            initializeProfiledArgumentTypes(args);
        } else if (profiledArgumentTypes != null) {
            if (profiledArgumentTypes.length != args.length) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                profiledArgumentTypesAssumption.invalidate();
                profiledArgumentTypes = null;
            } else if (TruffleArgumentTypeSpeculation.getValue() && profiledArgumentTypesAssumption.isValid()) {
                for (int i = 0; i < profiledArgumentTypes.length; i++) {
                    if (profiledArgumentTypes[i] != null && !profiledArgumentTypes[i].isInstance(args[i])) {
                        CompilerDirectives.transferToInterpreterAndInvalidate();
                        updateProfiledArgumentTypes(args);
                        break;
                    }
                }
            }
        }
    }

    private void initializeProfiledArgumentTypes(Object[] args) {
        CompilerAsserts.neverPartOfCompilation();
        profiledArgumentTypesAssumption = Truffle.getRuntime().createAssumption("Profiled Argument Types");
        profiledArgumentTypes = new Class<?>[args.length];
        if (TruffleArgumentTypeSpeculation.getValue()) {
            for (int i = 0; i < args.length; i++) {
                profiledArgumentTypes[i] = classOf(args[i]);
            }
        }
    }

    private void updateProfiledArgumentTypes(Object[] args) {
        CompilerAsserts.neverPartOfCompilation();
        profiledArgumentTypesAssumption.invalidate();
        for (int j = 0; j < profiledArgumentTypes.length; j++) {
            profiledArgumentTypes[j] = joinTypes(profiledArgumentTypes[j], classOf(args[j]));
        }
        profiledArgumentTypesAssumption = Truffle.getRuntime().createAssumption("Profiled Argument Types");
    }

    private static Class<?> classOf(Object arg) {
        return arg != null ? arg.getClass() : null;
    }

    private static Class<?> joinTypes(Class<?> class1, Class<?> class2) {
        if (class1 == class2) {
            return class1;
        } else if (class1 == null || class2 == null) {
            return null;
        } else if (class1.isAssignableFrom(class2)) {
            return class1;
        } else if (class2.isAssignableFrom(class1)) {
            return class2;
        } else {
            return Object.class;
        }
    }

    @TruffleCallBoundary
    private Object callBoundary(Object[] args) {
        if (CompilerDirectives.inInterpreter()) {
            // We are called and we are still in Truffle interpreter mode.
            interpreterCall();
        } else {
            // We come here from compiled code (i.e., we have been inlined).
        }

        return callRoot(args);
    }

    @Override
    public void invalidate() {
        this.runtime.invalidateInstalledCode(this);
        invalidateInlining();
    }

    protected void invalidate(Node oldNode, Node newNode, CharSequence reason) {
        if (isValid()) {
            CompilerAsserts.neverPartOfCompilation();
            invalidate();
            compilationProfile.reportInvalidated();
            logOptimizedInvalidated(this, oldNode, newNode, reason);
        }
        cancelInstalledTask(oldNode, newNode, reason);
        invalidateInlining();
    }

    public void invalidateInlining() {
        if (inliningPerformed) {
            inliningPerformed = false;
            getRootNode().accept(new NodeVisitor() {
                public boolean visit(Node node) {
                    if (node instanceof OptimizedDirectCallNode) {
                        OptimizedDirectCallNode callNode = (OptimizedDirectCallNode) node;
                        if (callNode.isInlined()) {
                            callNode.resetInlining();
                        }
                    }
                    return true;
                }
            });
        }
    }

    private void cancelInstalledTask(Node oldNode, Node newNode, CharSequence reason) {
        if (this.runtime.cancelInstalledTask(this)) {
            logOptimizingUnqueued(this, oldNode, newNode, reason);
            compilationProfile.reportInvalidated();
        }
    }

    private void interpreterCall() {
        CompilerAsserts.neverPartOfCompilation();
        if (this.isValid()) {
            // Stubs were deoptimized => reinstall.
            this.runtime.reinstallStubs();
        } else {
            compilationProfile.reportInterpreterCall();
            if (TruffleCallTargetProfiling.getValue()) {
                callCount++;
            }
            if (compilationPolicy.shouldCompile(compilationProfile)) {
                compile();
            }
        }
    }

    public void compile() {
        if (!runtime.isCompiling(this)) {
            performInlining();
            logOptimizingQueued(this);
            runtime.compile(this, TruffleBackgroundCompilation.getValue());
        }
    }

    public void compilationFinished(Throwable t) {
        if (t == null) {
            // Compilation was successful.
        } else {
            compilationPolicy.recordCompilationFailure(t);
            logOptimizingFailed(this, t.getMessage());
            if (t instanceof BailoutException) {
                // Bailout => move on.
            } else {
                if (TruffleCompilationExceptionsAreFatal.getValue()) {
                    t.printStackTrace(OUT);
                    System.exit(-1);
                }
            }
        }
    }

    protected final Object callProxy(VirtualFrame frame) {
        try {
            return getRootNode().execute(frame);
        } finally {
            // this assertion is needed to keep the values from being cleared as non-live locals
            assert frame != null && this != null;
        }
    }

    public final int getKnownCallSiteCount() {
        return callSitesKnown.get();
    }

    public final void incrementKnownCallSites() {
        callSitesKnown.incrementAndGet();
    }

    public final void decrementKnownCallSites() {
        callSitesKnown.decrementAndGet();
    }

    public final OptimizedCallTarget getSplitSource() {
        return splitSource;
    }

    public final void setSplitSource(OptimizedCallTarget splitSource) {
        this.splitSource = splitSource;
    }

    @Override
    public String toString() {
        String superString = rootNode.toString();
        if (isValid()) {
            superString += " <opt>";
        }
        if (splitSource != null) {
            superString += " <split-" + splitIndex + "-" + argumentStamp.toStringShort() + ">";
        }
        return superString;
    }

    public CompilationProfile getCompilationProfile() {
        return compilationProfile;
    }

    public final Object callInlined(Object[] arguments) {
        if (CompilerDirectives.inInterpreter()) {
            compilationProfile.reportInlinedCall();
        }
        VirtualFrame frame = createFrame(getRootNode().getFrameDescriptor(), arguments);
        return callProxy(frame);
    }

    public final void performInlining() {
        if (!TruffleFunctionInlining.getValue()) {
            return;
        }
        if (inliningPerformed) {
            return;
        }
        TruffleInliningHandler handler = new TruffleInliningHandler(new DefaultInliningPolicy());
        TruffleInliningDecision result = handler.decideInlining(this, 0);
        performInlining(result);
        logInliningDecision(result);
    }

    private static void performInlining(TruffleInliningDecision result) {
        if (result.getCallTarget().inliningPerformed) {
            return;
        }
        result.getCallTarget().inliningPerformed = true;
        for (TruffleInliningProfile profile : result) {
            profile.getCallNode().inline();
            TruffleInliningDecision recursiveResult = profile.getRecursiveResult();
            if (recursiveResult != null) {
                performInlining(recursiveResult);
            }
        }
    }

    public final Object callRoot(Object[] originalArguments) {
        Object[] args = originalArguments;
        if (this.profiledArgumentTypesAssumption != null && CompilerDirectives.inCompiledCode() && profiledArgumentTypesAssumption.isValid()) {
            args = CompilerDirectives.unsafeCast(castArrayFixedLength(args, profiledArgumentTypes.length), Object[].class, true, true);
            if (TruffleArgumentTypeSpeculation.getValue()) {
                args = castArguments(args);
            }
        }

        VirtualFrame frame = createFrame(getRootNode().getFrameDescriptor(), args);
        Object result = callProxy(frame);

        // Profile call return type
        if (profiledReturnTypeAssumption == null) {
            if (TruffleReturnTypeSpeculation.getValue()) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                profiledReturnType = (result == null ? null : result.getClass());
                profiledReturnTypeAssumption = Truffle.getRuntime().createAssumption("Profiled Return Type");
            }
        } else if (profiledReturnType != null) {
            if (result == null || profiledReturnType != result.getClass()) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                profiledReturnType = null;
                profiledReturnTypeAssumption.invalidate();
            }
        }

        return result;
    }

    @ExplodeLoop
    private Object[] castArguments(Object[] originalArguments) {
        Object[] castArguments = new Object[profiledArgumentTypes.length];
        for (int i = 0; i < profiledArgumentTypes.length; i++) {
            castArguments[i] = profiledArgumentTypes[i] != null ? CompilerDirectives.unsafeCast(originalArguments[i], profiledArgumentTypes[i], true, true) : originalArguments[i];
        }
        return castArguments;
    }

    private static Object castArrayFixedLength(Object[] args, @SuppressWarnings("unused") int length) {
        return args;
    }

    public static FrameWithoutBoxing createFrame(FrameDescriptor descriptor, Object[] args) {
        return new FrameWithoutBoxing(descriptor, args);
    }

    @Override
    public void reportLoopCount(int count) {
        compilationProfile.reportLoopCount(count);
    }

    @Override
    public void nodeReplaced(Node oldNode, Node newNode, CharSequence reason) {
        compilationProfile.reportNodeReplaced();
        invalidate(oldNode, newNode, reason);
    }

    public Map<String, Object> getDebugProperties() {
        Map<String, Object> properties = new LinkedHashMap<>();
        addASTSizeProperty(this, properties);
        properties.putAll(getCompilationProfile().getDebugProperties());
        return properties;

    }

}
