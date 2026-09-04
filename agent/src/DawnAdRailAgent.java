import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LocalVariableNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Patches the Dawn launcher into a more compact layout. Six independent
 * bytecode patches, each on a different class/method, all applied by this
 * one agent.
 *
 * LOADING: this agent is loaded as a static -javaagent, wired into Dawn's
 * own jpackage launch config (app\Dawn (Feather).cfg, [JavaOptions] section
 * -- see install-dawn-patch.ps1 / uninstall-dawn-patch.ps1 in this
 * directory). This is a PERSISTENT change to the installed app: every
 * launch of Dawn is patched, including a normal double-click or Start Menu
 * launch, not just a special shortcut. This was NOT the original design --
 * an earlier version used a toggle-only launcher that started Dawn plain
 * and attached via the JVM Attach API afterward -- but patch 5 below
 * (window sizing) turned out to depend on winning a startup race that
 * proved essentially unwinnable that way (0/20 in testing, even with an
 * aggressive retry loop), so a static -javaagent (which loads before ANY
 * application code runs, eliminating the race entirely) is the only
 * reliable option for that specific patch. Patches 1-3 don't have this
 * problem (they apply on every recomposition, not just once at startup)
 * and would have been fine either way.
 *
 * A Dawn auto-update will likely regenerate the .cfg file and silently
 * revert to stock (ads back, full-size window) until re-installed; run
 * install-dawn-patch.ps1 again if that happens.
 *
 * 1. gg.dawn.launcher.desktop.ads.M.b()V -> return;
 *    Ad-rail browser never activates. (Original fix; kept as harmless
 *    defense-in-depth even though the user primarily relies on DNS-level ad
 *    blocking now.) NOTE: this alone does NOT remove the visible rail --
 *    see patch 3b below. It only stops the ad content itself from loading.
 *
 * 2. gg.dawn.ui.components.ba.a(II)I -> return N;
 *    This is the single source-of-truth "start cursor" for the right-to-left
 *    packing of the top-nav utility cluster (MCPVP toggle / account pill /
 *    coin pill / STORE). It's normally anchored near the ad-rail boundary,
 *    leaving a big dead gap after the left-side HOME/PROFILES/COMMUNITY/
 *    COSMETICS/HOSTING tabs (which pack left-to-right from a fixed x=139
 *    with widths 84/131/147/147/120 -- ending around x=780). Returning a
 *    constant near there instead pulls the whole cluster left.
 *
 * 3a. gg.dawn.launcher.i.e.bk.a(ae,aw,ay,Q,bd)V -- truncated mid-method.
 *    Renders the whole Dawn-Home hero block: intro, keyart, edition tabs,
 *    play button, stat tiles, THEN a bounds calc + the "PARTNER SERVERS"
 *    panel. Only the tail (from the bounds calc onward) is removed --
 *    everything before it is left untouched. Unlike M.b(), this can't be
 *    truncated from the very start of the method.
 *
 * 3b. gg.dawn.launcher.i.e.bk.a(ae,aw,aZ)V -> return;
 *    A separate overload of `a` (same class) that renders the right-rail's
 *    themed "Card" CONTAINER (id "launcher-right-rail") -- runs
 *    unconditionally regardless of whether the ad inside it (patch 1) is
 *    active. Neutralized as harmless cleanup. Proven, via a temporary
 *    call-counting diagnostic, NOT to be the source of the visible black
 *    bar -- see patch 6, the actual cause.
 *
 * 6. gg.dawn.ui.az.<init>(FFFFFLgg/dawn/ui/aM;FILkotlin/jvm/internal/
 *    DefaultConstructorMarker;)V -- one LDC float operand edited in place
 *    (1544.0f -> 1920.0f). THE ACTUAL SOURCE of the black bar.
 *    gg.dawn.ui.az ("DawnRuntimeFramePolicy") hardcodes a 1920x1106
 *    BASE/reference design frame plus a "right rail start" of 1544.0f
 *    reference-units into that frame, as Kotlin default-constructor-arg
 *    values. Every content box's pixel width is computed as
 *    (railStart * scale), where scale = actualWindowWidthPx / 1920 -- so
 *    content ALWAYS occupies exactly railStart/1920 (~80.4%) of whatever
 *    the window's actual width is, a fixed FRACTION baked in at
 *    construction, not a fixed pixel amount and not a floor. This is why
 *    patch 4 (shrinking the window) never closed the gap on its own, and
 *    why bk.d(ay) -- which just re-derives this same box -- returned
 *    1543/1544 at every window width tried (confirmed via a live
 *    print-return-value diagnostic): the math (railStart/scale) cancels
 *    scale out and reproduces the original hardcoded constant regardless
 *    of window size. Confirmed by the numbers matching to within rounding
 *    at two different window widths (1544/1920 = 0.8042; measured gap
 *    ratios were 0.8038 and 0.8056). Fix: raise the default rail-start to
 *    equal the base width (1920.0f), making the content box always
 *    compute to the full window -- zero reserved margin, at any window
 *    width.
 *
 *    DEBUGGING NOTE: an earlier attempt to prove/disprove bk.d(ay)'s
 *    involvement via a live diagnostic briefly shipped a stack-imbalanced
 *    bytecode edit (an extra value left on the stack across a
 *    print(String) call) that caused a VerifyError in gg.dawn.launcher.i.
 *    e.bj's <clinit> at startup, crashing the JVM before printing anything
 *    -- which looked like "this method is never called" but was actually
 *    "the app never got far enough to call it". Caught by running the
 *    patched class through ASM's CheckClassAdapter.verify() (see
 *    Verify.java) with Dawn's own jars on the classpath before touching
 *    the live install again; worth doing for any future diagnostic patch
 *    that inserts non-trivial bytecode, not just the structural ones.
 *
 * 4. gg.dawn.launcher.desktop.MainKt.a(DD Ljava/lang/String;)Ljava/awt/Dimension;
 *    -- one SIPUSH operand edited in place (1640 -> smaller). This single
 *    function is the hardcoded source of both the initial window size and
 *    the continuously-reasserted minimum size (confirmed: a Windows-only
 *    "Corrective" resize listener forces the window back to at least this
 *    size on every manual resize attempt) -- so shrinking this one constant
 *    fixes both without needing to touch that listener at all.
 *
 * 5. gg.dawn.launcher.desktop.T.a(w)Lgg/dawn/launcher/desktop/H; -> return null;
 *    Reads a PERSISTED window size (from a previous, unpatched launch) and,
 *    if present, wins over Patch 4's new default via a
 *    coerceAtLeast(persisted, newDefault) -- confirmed by testing: with only
 *    Patch 4 applied, the window still opened at the old 1640x923 because
 *    this session had already launched Dawn many times unpatched, leaving a
 *    persisted 1640x923 on disk. Making this always return null (as if
 *    nothing were persisted) forces the "fresh default" branch instead --
 *    which has its OWN embedded 1640/923 literals, so patch 4 is extended
 *    to fix a *second* method (the combiner that calls T.a()) too.
 *
 *    IMPORTANT -- this value also gets persisted BACK to
 *    %APPDATA%\.dawn\launcher-settings.json (key "launcherWindowWidthPoints")
 *    by Dawn itself while running. With a static -javaagent this no longer
 *    matters functionally (T.a() is patched before its first-ever call, so
 *    the file is never read) but install-dawn-patch.ps1 rewrites it anyway
 *    once at install time, purely so nothing in the app's own UI displays a
 *    stale "1640" if it ever reads that key for a different purpose.
 *
 *    RACE CONDITION (why this needed a static -javaagent, not attach): the
 *    WindowState is built exactly once per process, very early, from a
 *    dedicated "gg.dawn.launcher.desktop.startup.f" bootstrap path, and
 *    Compose's remember{} then holds onto whatever H it got FOR THE REST OF
 *    THE SESSION -- retransforming T/MainKt *after* that first call has
 *    already run has no effect, because the (stale) computed value is
 *    cached, not recomputed from the (by-then-patched) function. Confirmed
 *    via a temporary constructor-level diagnostic that every `H` ever
 *    constructed in a session matched the patched width -- i.e. the patch
 *    logic itself was always correct, it just needed to land before this
 *    one early call, which attach-after-launch could not reliably do.
 */
public final class DawnAdRailAgent {

    private static final String M_CLASS = "gg/dawn/launcher/desktop/ads/M";
    private static final String M_METHOD = "b";
    private static final String M_DESC = "()V";

    private static final String BA_CLASS = "gg/dawn/ui/components/ba";
    private static final String BA_METHOD = "a";
    private static final String BA_DESC = "(II)I";
    private static final int BA_NEW_ANCHOR = 1920;

    private static final String BK_CLASS = "gg/dawn/launcher/i/e/bk";
    private static final String BK_METHOD = "a";
    private static final String BK_DESC =
            "(Lgg/dawn/ui/ae;Lgg/dawn/ui/aw;Lgg/dawn/ui/ay;Lgg/dawn/ui/components/Q;Lgg/dawn/launcher/i/e/bd;)V";
    private static final String BK_ANCHOR_OWNER = "gg/dawn/launcher/i/e/bk";
    private static final String BK_ANCHOR_NAME = "m";
    private static final String BK_ANCHOR_DESC = "(Lgg/dawn/ui/ay;)Lgg/dawn/launcher/i/e/bu;";
    private static final int BK_ANCHOR_VAR_SLOT = 2; // the method's own 3rd param (ay2)

    // The right-rail CONTAINER itself (the themed "Card" box that the ad --
    // or, pre-Patch-1, the ad browser -- sits inside). Renders unconditionally
    // regardless of ad state; neutralizing M.b() alone only stops the ad
    // content from loading INSIDE this box, not the box itself, which is why
    // a solid dark rectangle remained even after Patch 1 and the window
    // shrink. Same BK_CLASS, different overload of `a`.
    private static final String BK_RAIL_METHOD = "a";
    private static final String BK_RAIL_DESC = "(Lgg/dawn/ui/ae;Lgg/dawn/ui/aw;Lgg/dawn/launcher/i/e/aZ;)V";

    // THE ACTUAL black-bar cause: gg.dawn.ui.az ("DawnRuntimeFramePolicy")
    // hardcodes a 1920x1106 BASE/reference design frame and a "right rail
    // start" of 1544.0f reference-units into that frame (its Kotlin
    // default-args constructor). Every ay's content-box width is derived as
    // (railStart * scale) where scale = actualWindowWidthPx / 1920 -- so
    // content always occupies exactly railStart/1920 (~80.4%) of whatever
    // the window's actual width is, by construction, REGARDLESS of window
    // size. This is why patching the window narrower (patch 4) never closed
    // the gap: the reservation is a fixed FRACTION, not a fixed pixel
    // amount or a floor -- confirmed by live diagnostic (bk.d(ay), which
    // just re-derives this same box, returned 1543/1544 at every window
    // width tested) and by the math (1544/1920 = 0.8042, matching the
    // measured gap ratio at multiple window widths to within rounding).
    // Fix: raise the default "right rail start" to equal the base width
    // (1920.0f) so the content box always computes to the FULL window --
    // zero reserved margin.
    private static final String AZ_CLASS = "gg/dawn/ui/az";
    private static final String AZ_METHOD = "<init>";
    private static final String AZ_DESC =
            "(FFFFFLgg/dawn/ui/aM;FILkotlin/jvm/internal/DefaultConstructorMarker;)V";
    private static final float AZ_OLD_RAIL_START = 1544.0f;
    private static final float AZ_NEW_RAIL_START = 1920.0f; // == base width -> zero reserved margin

    // THE REAL fix. az's default-args constructor (patched above) only fires
    // when a caller OMITS that argument -- but the app's actual singleton
    // frame-policy instance is built in gg.dawn.launcher.i.e.bj's <clinit>
    // via `new az(1920f, 1080f, 92f, 1544f, 0f, aM.AdaptiveWidth, 0f, 80,
    // null)`, which passes 1544f EXPLICITLY (bitmask 80 = 0x50 only
    // defaults the pixelSnap/overflowRatio params, not railStart) --
    // bypassing the AZ_CLASS patch entirely. This is why that patch alone
    // (verified applied, verified valid bytecode) still produced an
    // unchanged black bar. Only one LDC 1544.0f exists in the whole
    // decompiled class, and it's this exact call, so this is safe to target
    // by value within the scope of <clinit> alone.
    private static final String BJ_CLASS = "gg/dawn/launcher/i/e/bj";
    private static final String BJ_METHOD = "<clinit>";
    private static final String BJ_DESC = "()V";
    private static final float BJ_OLD_RAIL_START = 1544.0f;
    private static final float BJ_NEW_RAIL_START = 1920.0f;

    private static final String MAINKT_CLASS = "gg/dawn/launcher/desktop/MainKt";
    private static final String MAINKT_METHOD = "a";
    private static final String MAINKT_DESC = "(DDLjava/lang/String;)Ljava/awt/Dimension;";
    private static final String MAINKT_COMBINER_DESC =
            "(Lkotlin/jvm/functions/Function1;Lkotlin/jvm/functions/Function0;Lkotlin/jvm/functions/Function0;"
            + "Lkotlin/jvm/functions/Function0;Lkotlin/jvm/functions/Function0;)Lgg/dawn/launcher/desktop/H;";
    private static final int MAINKT_OLD_WIDTH = 1640;
    static final int MAINKT_NEW_WIDTH = 1920; // must match install-dawn-patch.ps1's settings-file rewrite

    private static final String T_CLASS = "gg/dawn/launcher/desktop/T";
    private static final String T_METHOD = "a";
    private static final String T_DESC = "(Lgg/dawn/launcher/desktop/d/w;)Lgg/dawn/launcher/desktop/H;";

    private static final Set<String> WATCHED_CLASSES = Collections.unmodifiableSet(new HashSet<>(
            java.util.Arrays.asList(M_CLASS, BA_CLASS, BK_CLASS, MAINKT_CLASS, T_CLASS, AZ_CLASS, BJ_CLASS)));

    /** Static (-javaagent) entry point: install before any Dawn class loads. */
    public static void premain(String agentArgs, Instrumentation inst) {
        log("premain: agent attached, watching for " + WATCHED_CLASSES);
        inst.addTransformer(newTransformer(), true);
    }

    /**
     * Dynamic-attach entry point (java.lang.instrument's Agent-Class): used
     * when attaching to an already-running Dawn process. Registers the
     * transformer for future loads, and retransforms any watched class
     * immediately if it was already loaded before we got here.
     */
    public static void agentmain(String agentArgs, Instrumentation inst) {
        log("agentmain: agent attached, watching for " + WATCHED_CLASSES);
        inst.addTransformer(newTransformer(), true);
        if (!inst.isRetransformClassesSupported()) {
            log("WARNING: retransformClasses not supported by this JVM -- can only catch classes not yet loaded.");
            return;
        }
        Set<String> dotted = new HashSet<>();
        for (String c : WATCHED_CLASSES) {
            dotted.add(c.replace('/', '.'));
        }
        for (Class<?> c : inst.getAllLoadedClasses()) {
            if (dotted.contains(c.getName())) {
                try {
                    log("class " + c.getName() + " already loaded -- retransforming");
                    inst.retransformClasses(c);
                } catch (Throwable t) {
                    log("FAILED to retransform " + c.getName() + ": " + t);
                    t.printStackTrace();
                }
            }
        }
    }

    private static ClassFileTransformer newTransformer() {
        return new ClassFileTransformer() {
            @Override
            public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                                     ProtectionDomain protectionDomain, byte[] classfileBuffer) {
                if (!WATCHED_CLASSES.contains(className)) {
                    return null;
                }
                try {
                    return patchByClassName(className, classfileBuffer);
                } catch (Throwable t) {
                    log("FAILED to patch " + className + ": " + t);
                    t.printStackTrace();
                    return null;
                }
            }
        };
    }

    private static byte[] patchByClassName(String className, byte[] bytes) {
        if (M_CLASS.equals(className)) {
            return patchReturn(bytes, M_CLASS, M_METHOD, M_DESC);
        }
        if (BA_CLASS.equals(className)) {
            return patchReturnInt(bytes, BA_CLASS, BA_METHOD, BA_DESC, BA_NEW_ANCHOR);
        }
        if (BK_CLASS.equals(className)) {
            return patchBk(bytes);
        }
        if (MAINKT_CLASS.equals(className)) {
            return patchMainKt(bytes);
        }
        if (T_CLASS.equals(className)) {
            return patchReturnNull(bytes, T_CLASS, T_METHOD, T_DESC);
        }
        if (AZ_CLASS.equals(className)) {
            return patchReplaceFloatConstant(bytes, AZ_CLASS, AZ_METHOD, AZ_DESC, AZ_OLD_RAIL_START,
                    AZ_NEW_RAIL_START);
        }
        if (BJ_CLASS.equals(className)) {
            return patchReplaceFloatConstant(bytes, BJ_CLASS, BJ_METHOD, BJ_DESC, BJ_OLD_RAIL_START,
                    BJ_NEW_RAIL_START);
        }
        return null;
    }

    /**
     * bk needs two independent edits: removing the tail of the Home-page
     * hero method (Partner Servers panel) and neutralizing the separate
     * right-rail container method entirely. Chained so both land in the one
     * class-load event.
     */
    private static byte[] patchBk(byte[] bytes) {
        byte[] step1 = patchTruncateAtInvokeStatic(bytes, BK_CLASS, BK_METHOD, BK_DESC,
                BK_ANCHOR_OWNER, BK_ANCHOR_NAME, BK_ANCHOR_DESC, BK_ANCHOR_VAR_SLOT);
        byte[] afterStep1 = step1 != null ? step1 : bytes;

        byte[] step2 = patchReturn(afterStep1, BK_CLASS, BK_RAIL_METHOD, BK_RAIL_DESC);
        byte[] afterStep2 = step2 != null ? step2 : afterStep1;

        return step1 != null || step2 != null ? afterStep2 : null;
    }

    /**
     * MainKt needs two independent edits: the Dimension-building method
     * (initial size + enforced minimum) and the size-combiner method (whose
     * "no persisted size" fallback branch has its own separate 1640/923
     * literals, reached once Patch 5 makes T.a() always return null). Chained
     * so both land in the one class-load event.
     */
    private static byte[] patchMainKt(byte[] bytes) {
        byte[] step1 = patchReplaceIntConstant(bytes, MAINKT_CLASS, MAINKT_METHOD, MAINKT_DESC,
                MAINKT_OLD_WIDTH, MAINKT_NEW_WIDTH);
        byte[] afterStep1 = step1 != null ? step1 : bytes;

        byte[] step2 = patchReplaceIntConstant(afterStep1, MAINKT_CLASS, MAINKT_METHOD, MAINKT_COMBINER_DESC,
                MAINKT_OLD_WIDTH, MAINKT_NEW_WIDTH);
        byte[] afterStep2 = step2 != null ? step2 : afterStep1;

        return step1 != null || step2 != null ? afterStep2 : null;
    }

    // ---- Patch 1: truncate a void method to a bare `return;` ----

    static byte[] patchReturn(byte[] original, String classNameForLog, String methodName, String methodDesc) {
        ClassNode cn = readClass(original);
        MethodNode target = findMethod(cn, methodName, methodDesc);
        if (target == null) {
            warnMissing(classNameForLog, methodName, methodDesc);
            return null;
        }

        InsnList insns = new InsnList();
        insns.add(new InsnNode(Opcodes.RETURN));
        target.instructions = insns;
        target.tryCatchBlocks.clear();
        if (target.localVariables != null) {
            target.localVariables.clear();
        }

        log("patched " + classNameForLog + "." + methodName + methodDesc + " -> return;");
        return writeClass(cn);
    }

    // ---- Patch 5: truncate a reference-returning method to `return null;` ----

    static byte[] patchReturnNull(byte[] original, String classNameForLog, String methodName, String methodDesc) {
        ClassNode cn = readClass(original);
        MethodNode target = findMethod(cn, methodName, methodDesc);
        if (target == null) {
            warnMissing(classNameForLog, methodName, methodDesc);
            return null;
        }

        InsnList insns = new InsnList();
        insns.add(new InsnNode(Opcodes.ACONST_NULL));
        insns.add(new InsnNode(Opcodes.ARETURN));
        target.instructions = insns;
        target.tryCatchBlocks.clear();
        if (target.localVariables != null) {
            target.localVariables.clear();
        }

        log("patched " + classNameForLog + "." + methodName + methodDesc + " -> return null;");
        return writeClass(cn);
    }

    // ---- Patch 2: truncate an int-returning method to `return N;` ----

    static byte[] patchReturnInt(byte[] original, String classNameForLog, String methodName, String methodDesc,
                                  int constant) {
        ClassNode cn = readClass(original);
        MethodNode target = findMethod(cn, methodName, methodDesc);
        if (target == null) {
            warnMissing(classNameForLog, methodName, methodDesc);
            return null;
        }

        InsnList insns = new InsnList();
        pushInt(insns, constant);
        insns.add(new InsnNode(Opcodes.IRETURN));
        target.instructions = insns;
        target.tryCatchBlocks.clear();
        if (target.localVariables != null) {
            target.localVariables.clear();
        }

        log("patched " + classNameForLog + "." + methodName + methodDesc + " -> return " + constant + ";");
        return writeClass(cn);
    }

    // ---- Patch 3: truncate a void method starting at a specific invokestatic call ----

    static byte[] patchTruncateAtInvokeStatic(byte[] original, String classNameForLog, String methodName,
                                               String methodDesc, String anchorOwner, String anchorName,
                                               String anchorDesc, int expectedVarSlot) {
        ClassNode cn = readClass(original);
        MethodNode target = findMethod(cn, methodName, methodDesc);
        if (target == null) {
            warnMissing(classNameForLog, methodName, methodDesc);
            return null;
        }

        MethodInsnNode anchorCall = null;
        for (AbstractInsnNode insn : target.instructions.toArray()) {
            if (insn.getOpcode() == Opcodes.INVOKESTATIC) {
                MethodInsnNode min = (MethodInsnNode) insn;
                if (anchorOwner.equals(min.owner) && anchorName.equals(min.name) && anchorDesc.equals(min.desc)) {
                    anchorCall = min;
                    break;
                }
            }
        }
        if (anchorCall == null) {
            log("WARNING: anchor call " + anchorOwner + "." + anchorName + anchorDesc + " not found inside "
                    + classNameForLog + "." + methodName + methodDesc + " -- Dawn may have updated; not patched.");
            return null;
        }

        AbstractInsnNode cutPoint = anchorCall.getPrevious();
        if (!(cutPoint instanceof VarInsnNode) || cutPoint.getOpcode() != Opcodes.ALOAD
                || ((VarInsnNode) cutPoint).var != expectedVarSlot) {
            log("WARNING: instruction preceding the anchor call in " + classNameForLog + "." + methodName
                    + methodDesc + " was not the expected ALOAD " + expectedVarSlot
                    + " -- Dawn may have changed this method's shape; not patched (refusing to guess).");
            return null;
        }

        Set<AbstractInsnNode> removed = new HashSet<>();
        AbstractInsnNode current = cutPoint;
        while (current != null) {
            AbstractInsnNode next = current.getNext();
            removed.add(current);
            target.instructions.remove(current);
            current = next;
        }
        target.instructions.add(new InsnNode(Opcodes.RETURN));

        if (target.tryCatchBlocks != null) {
            target.tryCatchBlocks.removeIf(tcb -> removed.contains(tcb.start) || removed.contains(tcb.end)
                    || removed.contains(tcb.handler));
        }
        if (target.localVariables != null) {
            target.localVariables.removeIf(lv -> referencesRemoved(lv, removed));
        }

        log("patched " + classNameForLog + "." + methodName + methodDesc
                + " -> truncated at " + anchorOwner + "." + anchorName + anchorDesc + " call, then return;");
        return writeClass(cn);
    }

    private static boolean referencesRemoved(LocalVariableNode lv, Set<AbstractInsnNode> removed) {
        return removed.contains(lv.start) || removed.contains(lv.end);
    }

    // ---- Patch 4: replace one int constant operand in place ----

    static byte[] patchReplaceIntConstant(byte[] original, String classNameForLog, String methodName,
                                           String methodDesc, int oldValue, int newValue) {
        ClassNode cn = readClass(original);
        MethodNode target = findMethod(cn, methodName, methodDesc);
        if (target == null) {
            warnMissing(classNameForLog, methodName, methodDesc);
            return null;
        }

        int replacedCount = 0;
        for (AbstractInsnNode insn : target.instructions.toArray()) {
            if (isIntConstant(insn, oldValue)) {
                InsnList replacement = new InsnList();
                pushInt(replacement, newValue);
                target.instructions.insert(insn, replacement);
                target.instructions.remove(insn);
                replacedCount++;
            }
        }
        if (replacedCount == 0) {
            log("WARNING: int constant " + oldValue + " not found inside " + classNameForLog + "." + methodName
                    + methodDesc + " -- Dawn may have updated; not patched.");
            return null;
        }

        log("patched " + classNameForLog + "." + methodName + methodDesc + " -> replaced " + replacedCount
                + " occurrence(s) of " + oldValue + " with " + newValue);
        return writeClass(cn);
    }

    private static boolean isIntConstant(AbstractInsnNode insn, int value) {
        if (insn instanceof IntInsnNode && (insn.getOpcode() == Opcodes.SIPUSH || insn.getOpcode() == Opcodes.BIPUSH)) {
            return ((IntInsnNode) insn).operand == value;
        }
        if (insn instanceof LdcInsnNode) {
            Object cst = ((LdcInsnNode) insn).cst;
            return cst instanceof Integer && (Integer) cst == value;
        }
        return false;
    }

    // ---- Patch 6: replace one float (LDC) constant operand in place ----

    static byte[] patchReplaceFloatConstant(byte[] original, String classNameForLog, String methodName,
                                             String methodDesc, float oldValue, float newValue) {
        ClassNode cn = readClass(original);
        MethodNode target = findMethod(cn, methodName, methodDesc);
        if (target == null) {
            warnMissing(classNameForLog, methodName, methodDesc);
            return null;
        }

        int replacedCount = 0;
        for (AbstractInsnNode insn : target.instructions.toArray()) {
            if (insn instanceof LdcInsnNode) {
                Object cst = ((LdcInsnNode) insn).cst;
                if (cst instanceof Float && (Float) cst == oldValue) {
                    target.instructions.set(insn, new LdcInsnNode(newValue));
                    replacedCount++;
                }
            }
        }
        if (replacedCount == 0) {
            log("WARNING: float constant " + oldValue + " not found inside " + classNameForLog + "." + methodName
                    + methodDesc + " -- Dawn may have updated; not patched.");
            return null;
        }

        log("patched " + classNameForLog + "." + methodName + methodDesc + " -> replaced " + replacedCount
                + " occurrence(s) of " + oldValue + "f with " + newValue + "f");
        return writeClass(cn);
    }

    // ---- shared helpers ----

    private static void pushInt(InsnList insns, int value) {
        if (value >= -1 && value <= 5) {
            insns.add(new InsnNode(Opcodes.ICONST_0 + value));
        } else if (value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE) {
            insns.add(new IntInsnNode(Opcodes.BIPUSH, value));
        } else if (value >= Short.MIN_VALUE && value <= Short.MAX_VALUE) {
            insns.add(new IntInsnNode(Opcodes.SIPUSH, value));
        } else {
            insns.add(new LdcInsnNode(value));
        }
    }

    private static ClassNode readClass(byte[] bytes) {
        ClassReader cr = new ClassReader(bytes);
        ClassNode cn = new ClassNode();
        cr.accept(cn, 0);
        return cn;
    }

    private static byte[] writeClass(ClassNode cn) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }

    private static void warnMissing(String classNameForLog, String methodName, String methodDesc) {
        log("WARNING: target method " + methodName + methodDesc + " not found in " + classNameForLog
                + " -- Dawn may have updated; not patched.");
    }

    private static MethodNode findMethod(ClassNode cn, String name, String desc) {
        List<MethodNode> methods = cn.methods;
        for (MethodNode mn : methods) {
            if (name.equals(mn.name) && desc.equals(mn.desc)) {
                return mn;
            }
        }
        return null;
    }

    private static void log(String msg) {
        System.err.println("[DawnAdRailAgent] " + msg);
    }

    /**
     * Offline self-test: `java -cp dawn-adrail-agent.jar DawnAdRailAgent <target> <in.class> <out.class>`
     * where target is one of: M, ba, bk, MainKt, T, az, bj
     */
    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            System.err.println("usage: DawnAdRailAgent <M|ba|bk|MainKt|T|az|bj> <in.class> <out.class>");
            System.exit(1);
            return;
        }
        String target = args[0];
        byte[] original = java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(args[1]));
        byte[] result;
        switch (target) {
            case "M":
                result = patchReturn(original, M_CLASS, M_METHOD, M_DESC);
                break;
            case "ba":
                result = patchReturnInt(original, BA_CLASS, BA_METHOD, BA_DESC, BA_NEW_ANCHOR);
                break;
            case "bk":
                result = patchBk(original);
                break;
            case "MainKt":
                result = patchMainKt(original);
                break;
            case "T":
                result = patchReturnNull(original, T_CLASS, T_METHOD, T_DESC);
                break;
            case "az":
                result = patchReplaceFloatConstant(original, AZ_CLASS, AZ_METHOD, AZ_DESC, AZ_OLD_RAIL_START,
                        AZ_NEW_RAIL_START);
                break;
            case "bj":
                result = patchReplaceFloatConstant(original, BJ_CLASS, BJ_METHOD, BJ_DESC, BJ_OLD_RAIL_START,
                        BJ_NEW_RAIL_START);
                break;
            default:
                throw new IllegalArgumentException("unknown target: " + target);
        }
        if (result == null) {
            throw new IllegalStateException("patch returned null -- target method/anchor not found");
        }
        java.nio.file.Files.write(java.nio.file.Paths.get(args[2]), result);
        System.out.println("wrote patched class to " + args[2]);
    }

    private DawnAdRailAgent() {
    }
}
