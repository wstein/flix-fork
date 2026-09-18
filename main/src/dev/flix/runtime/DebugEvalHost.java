package dev.flix.runtime;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * Runs a compiled expression inside a paused program, and hands back what it produced.
 *
 * <h2>Why the debuggee has to do this</h2>
 *
 * <p>A debugger can read a paused frame, and that is all it can do without running something. An
 * expression like {@code List.length(xs)} is a call: it needs the program's own classes, its own
 * heap, and the values held in the frame the user is looking at. Nothing outside the process has
 * those.
 *
 * <p>So the compiler produces a class for the expression and this defines it here, beside the code
 * it calls. It is delivered into every {@code --Xdebug} build for the same reason the generated
 * runtime is: a class that has to be loadable by the program's own loader must be on the program's
 * own classpath before the program starts.
 *
 * <h2>The loader, and why it is a child</h2>
 *
 * <p>Each artifact is defined in a fresh loader whose parent is the loader that holds the running
 * program. Parent-first delegation then does exactly the right thing without being asked: every
 * class the program already has resolves to the *running* one, and only genuinely new classes — the
 * expression's own, and any specialisation the program never needed — are defined here.
 *
 * <p>That is also what makes it safe to send more than is strictly required. The alternative,
 * deciding outside the process which classes are missing, means modelling the debuggee's loader
 * state from a debugger, and being wrong produces a {@link NoClassDefFoundError} at the worst
 * possible moment.
 *
 * <p>A loader per artifact rather than one shared: an expression edited in a watch is a *new*
 * artifact under a new name, and the old classes become unreachable and collectable. One shared
 * loader would accumulate every expression ever typed for the life of the session.
 *
 * <h2>What it returns</h2>
 *
 * <p>The value, unwrapped. A generated function returns a {@code Result}, which is the trampoline's
 * currency rather than the programmer's: a {@code Thunk} to be invoked again, a {@code Value}
 * holding the answer in one of nine fields, or a {@code Suspension} if the expression performed an
 * effect. The trampoline is run here — it is a loop, and running it over a debug connection would be
 * one round trip per bounce — and the field is read by the name the compiler chose for the
 * expression's erased type.
 *
 * <p>A suspension is refused rather than resumed. Resuming it would run the program's own effect
 * handlers, in a program that is stopped, and what came back would depend on handlers installed for
 * a computation the user did not start.
 *
 * <h2>Everything is a string or a plain array</h2>
 *
 * <p>The caller is a debugger reaching in over JDWP, where every argument has to be built inside the
 * debuggee one value at a time. Class files arrive as one Base64 string rather than as an array of
 * byte arrays, because that is one value to construct instead of thousands.
 */
public final class DebugEvalHost {

    private DebugEvalHost() {
    }

    /** Forces this class to be loaded before a debugger asks JDI for it. */
    public static void install() {
        // Loading is the installation; evaluation artifacts remain per-call and collectable.
    }

    /**
     * Defines {@code artifact} and calls {@code entryClass.entryMethod(args)}.
     *
     * @param artifact    the expression's classes, as {@code name=base64} entries separated by
     *                    {@code ;}
     * @param entryClass  the binary name of the class holding the expression
     * @param entryMethod the static method to call on it
     * @param valueField  which field of {@code Value} holds the result, for the expression's erased
     *                    type — {@code i32}, {@code o}, and so on
     * @param args        the frame's values, in the order the entry method declares them
     * @return whatever the expression produced, boxed if it is primitive
     * @throws DebugEvalException if the artifact cannot be defined, the entry point is not there, or
     *                            the expression suspended
     */
    public static Object evaluate(
            String artifact,
            String entryClass,
            String entryMethod,
            String valueField,
            Object[] args) {

        Class<?> entry = define(artifact, entryClass);
        Object[] given = args == null ? new Object[0] : args;
        Method method = entryPoint(entry, entryMethod, given.length);
        Object result = call(method, arguments(method, given, entry.getClassLoader()));
        return read(unwind(result), valueField);
    }

    /**
     * Defines every class in {@code artifact} and returns the entry class.
     *
     * <p>The loader's parent is the one that loaded this class, which is the one holding the running
     * program: this class is delivered into the program's own output, so it is loaded by the same
     * loader the program is.
     */
    private static Class<?> define(String artifact, String entryClass) {
        Map<String, byte[]> classes = parse(artifact);
        if (!classes.containsKey(entryClass)) {
            throw new DebugEvalException(
                    "the artifact does not contain the entry class " + entryClass + "; it has " + classes.keySet());
        }
        ClassLoader loader = new ArtifactLoader(classes, DebugEvalHost.class.getClassLoader());
        try {
            return Class.forName(entryClass, true, loader);
        } catch (ClassNotFoundException | LinkageError e) {
            throw new DebugEvalException("the expression's classes could not be loaded: " + e, e);
        }
    }

    /**
     * The entry method, found by name and arity rather than by descriptor.
     *
     * <p>By arity because the descriptor is the compiler's business: a parameter's erased type
     * depends on decisions the caller has no reason to reproduce, and the artifact declares exactly
     * one method by this name.
     */
    private static Method entryPoint(Class<?> entry, String name, int arity) {
        for (Method candidate : entry.getDeclaredMethods()) {
            // Or one more: a definition with no parameters still takes a unit argument, which the
            // caller has no way to read out of a frame. See `arguments`.
            boolean fits = candidate.getParameterCount() == arity
                    || (arity == 0 && candidate.getParameterCount() == 1);
            if (candidate.getName().equals(name) && fits) {
                candidate.setAccessible(true);
                return candidate;
            }
        }
        throw new DebugEvalException(
                "the artifact has no " + name + " of " + arity + " argument(s) on " + entry.getName());
    }

    /**
     * The arguments to pass, with the unit value supplied when the expression needed no frame values.
     *
     * Flix has no nullary definition, so an expression mentioning nothing from the frame still
     * compiles to a method of one argument. The caller cannot read that argument from anywhere —
     * it is not in the frame — so it is read from the runtime here.
     */
    private static Object[] arguments(Method method, Object[] given, ClassLoader loader) {
        if (method.getParameterCount() == given.length) {
            return given;
        }
        try {
            Class<?> unit = Class.forName(UNIT, true, loader);
            return new Object[]{unit.getField("INSTANCE").get(null)};
        } catch (ReflectiveOperationException e) {
            throw new DebugEvalException("the unit value could not be read from the runtime: " + e, e);
        }
    }

    private static Object call(Method method, Object[] args) {
        return call(method, null, args);
    }

    private static Object call(Method method, Object receiver, Object[] args) {
        try {
            return method.invoke(receiver, args);
        } catch (IllegalAccessException e) {
            throw new DebugEvalException("the expression is not callable: " + e, e);
        } catch (InvocationTargetException e) {
            // The expression itself threw. That is a result, not a defect in this machinery, and the
            // cause is what a user needs to see.
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new DebugEvalException("the expression threw " + cause, cause);
        }
    }

    /**
     * Runs the trampoline until the result is not a thunk.
     *
     * <p>A loop, deliberately here rather than in the debugger: each bounce would otherwise be a
     * separate call over the debug connection, and a recursive expression bounces once per call.
     */
    private static Object unwind(Object result) {
        Object current = result;
        while (current != null && isThunk(current)) {
            Method invoke;
            try {
                invoke = current.getClass().getMethod("invoke");
                invoke.setAccessible(true);
            } catch (ReflectiveOperationException e) {
                throw new DebugEvalException("the trampoline could not be run: " + e, e);
            }
            // Separated from the lookup on purpose. Most of an expression's work happens on a
            // bounce rather than in the first call, so this is where a throw usually surfaces --
            // and reporting it as a broken trampoline would tell a user their debugger is at fault
            // when it is their own code that threw.
            current = call(invoke, current, new Object[0]);
        }
        return current;
    }

    private static boolean isThunk(Object value) {
        for (Class<?> face : allInterfaces(value.getClass())) {
            if (face.getName().equals(THUNK)) {
                return true;
            }
        }
        return false;
    }

    private static java.util.List<Class<?>> allInterfaces(Class<?> type) {
        java.util.List<Class<?>> found = new java.util.ArrayList<>();
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            collectInterfaces(current, found);
        }
        return found;
    }

    private static void collectInterfaces(Class<?> type, java.util.List<Class<?>> into) {
        for (Class<?> face : type.getInterfaces()) {
            if (into.add(face)) {
                collectInterfaces(face, into);
            }
        }
    }

    /**
     * The answer, out of the {@code Value} the trampoline left.
     *
     * <p>{@code Value} carries a field per erased type and no discriminator, so which one holds the
     * answer is a question only the compiler can settle — it is told to us rather than guessed at.
     */
    private static Object read(Object result, String valueField) {
        if (result == null) {
            throw new DebugEvalException("the expression produced nothing at all");
        }
        if (isSuspension(result)) {
            throw new DebugEvalException(
                    "the expression performed an effect. Resuming it would run the program's own "
                            + "handlers while it is stopped, so it is refused rather than resumed.");
        }
        try {
            Field field = result.getClass().getField(valueField);
            return field.get(result);
        } catch (NoSuchFieldException e) {
            throw new DebugEvalException(
                    "the result has no field " + valueField + "; it is a " + result.getClass().getName()
                            + " implementing " + allInterfaces(result.getClass()), e);
        } catch (IllegalAccessException e) {
            throw new DebugEvalException("the result's " + valueField + " is not readable: " + e, e);
        }
    }

    private static boolean isSuspension(Object value) {
        return value.getClass().getName().equals(SUSPENSION);
    }

    /**
     * The runtime's own names, which are not the generated program's.
     *
     * The trampoline's types live in {@code dev.flix.runtime} and end in {@code $} —
     * {@code Result$}, {@code Thunk$}, {@code Value$} — while the program's classes live in
     * {@code dev.flix.gen}. Measured from a build rather than assumed: the first version of this
     * class looked for {@code dev.flix.gen.Thunk}, found nothing, skipped the trampoline entirely and
     * tried to read a result field off a function object.
     */
    private static final String THUNK = "dev.flix.runtime.Thunk$";

    private static final String SUSPENSION = "dev.flix.runtime.Suspension$";

    /**
     * The Flix unit value, which a definition with no parameters still takes one of.
     *
     * Flix has no nullary definition: {@code def f(): Int32} compiles to a method of one argument,
     * and the argument is unit. A caller reading arguments out of a paused frame has no such value
     * to read, so it is supplied here rather than made the caller's problem.
     */
    private static final String UNIT = "dev.flix.runtime.Unit$";

    /** Reads {@code name=base64;name=base64} into class files. */
    private static Map<String, byte[]> parse(String artifact) {
        Map<String, byte[]> classes = new HashMap<>();
        if (artifact == null || artifact.isEmpty()) {
            return classes;
        }
        for (String entry : artifact.split(";")) {
            int at = entry.indexOf('=');
            if (at < 0) {
                throw new DebugEvalException("malformed artifact entry: " + entry);
            }
            classes.put(entry.substring(0, at), Base64.getDecoder().decode(entry.substring(at + 1)));
        }
        return classes;
    }

    /**
     * Defines the artifact's classes, and delegates everything else to the running program.
     *
     * <p>Parent-first, which is the default and is what is wanted: a class the program already has
     * must resolve to the one the program is *running*, not to a fresh copy compiled a moment ago.
     * Only what the parent cannot find is defined here.
     */
    private static final class ArtifactLoader extends ClassLoader {

        private final Map<String, byte[]> classes;

        ArtifactLoader(Map<String, byte[]> classes, ClassLoader parent) {
            super(parent);
            this.classes = classes;
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            byte[] bytes = classes.get(name);
            if (bytes == null) {
                throw new ClassNotFoundException(name);
            }
            return defineClass(name, bytes, 0, bytes.length);
        }
    }
}
