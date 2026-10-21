package mcplink.tools;

import mcplink.McpLink;
import mcplink.Game;
import mcplink.J;
import mcplink.mcp.Schema;
import mcplink.mcp.ToolRegistry;
import mcplink.mcp.ToolRegistry.ToolError;
import com.google.gson.JsonArray;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import org.jetbrains.java.decompiler.main.decompiler.BaseDecompiler;
import org.jetbrains.java.decompiler.main.extern.IContextSource;
import org.jetbrains.java.decompiler.main.extern.IFernflowerLogger;
import org.jetbrains.java.decompiler.main.extern.IFernflowerPreferences;
import org.jetbrains.java.decompiler.main.extern.IResultSaver;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.jar.Manifest;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Reading the game's code from inside it: every class of the game, its libraries and the installed mods by name, a class's
 * members, and its source decompiled on demand (the 26.x jar keeps real names, parameters included). The source is the class
 * as shipped, before mixins change it at load time.
 */
public final class CodeTools {
    private CodeTools() {
    }

    private static final ClassLoader LOADER = CodeTools.class.getClassLoader();

    // ---------------------------------------------------------------- the class index

    /** Binary class name (a.b.Outer$Inner) to where it comes from (a mod id, or a library jar's name). */
    private static volatile Map<String, String> index;

    private static Map<String, String> index() throws IOException {
        Map<String, String> m = index;
        if (m != null) return m;
        synchronized (CodeTools.class) {
            if (index != null) return index;
            Map<String, String> out = new TreeMap<>();
            java.util.Set<Path> seenJars = new java.util.HashSet<>();
            for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
                String id = mod.getMetadata().getId();
                for (Path root : mod.getRootPaths()) walk(root, id, out);
                try {
                    // only mods loaded straight from a jar or folder have paths (nested jars don't)
                    mod.getOrigin().getPaths().forEach(p -> seenJars.add(p.toAbsolutePath().normalize()));
                } catch (UnsupportedOperationException ignored) {
                }
            }
            // libraries on the class path (brigadier, DataFixerUpper, JOML, netty...) that are not mods
            for (String entry : System.getProperty("java.class.path", "").split(File.pathSeparator)) {
                if (!entry.endsWith(".jar")) continue;
                Path jar = Path.of(entry).toAbsolutePath().normalize();
                if (seenJars.contains(jar) || !Files.isRegularFile(jar)) continue;
                try (FileSystem fs = FileSystems.newFileSystem(jar)) {
                    String name = jar.getFileName().toString().replaceAll("\\.jar$", "");
                    for (Path root : fs.getRootDirectories()) walk(root, name, out);
                } catch (Exception e) {
                    McpLink.LOG.debug("Could not index {}", jar, e);
                }
            }
            index = Collections.unmodifiableMap(out);
            return index;
        }
    }

    private static void walk(Path root, String origin, Map<String, String> out) {
        try (Stream<Path> s = Files.walk(root)) {
            s.forEach(p -> {
                String f = root.relativize(p).toString().replace('\\', '/');
                if (!f.endsWith(".class") || f.startsWith("META-INF/") || f.endsWith("module-info.class") || f.endsWith("package-info.class")) return;
                out.putIfAbsent(f.substring(0, f.length() - 6).replace('/', '.'), origin);
            });
        } catch (Exception e) {
            McpLink.LOG.debug("Could not index {}", root, e);
        }
    }

    /** A class by full name, or by its simple name (Display.BlockDisplay, BlockDisplay) when that is unique. */
    static String resolveName(String name) throws IOException {
        String n = name.trim().replace('/', '.');
        Map<String, String> idx = index();
        if (idx.containsKey(n)) return n;
        // nested classes written with dots: net.minecraft.world.entity.Display.BlockDisplay
        String dotted = n;
        while (dotted.contains(".")) {
            int i = dotted.lastIndexOf('.');
            dotted = dotted.substring(0, i) + "$" + dotted.substring(i + 1);
            if (idx.containsKey(dotted)) return dotted;
        }
        String suffix = n.replace('.', '$');
        List<String> hits = new ArrayList<>();
        for (String k : idx.keySet()) {
            String simple = k.substring(k.lastIndexOf('.') + 1);
            if (simple.equals(suffix) || simple.endsWith("$" + suffix)) hits.add(k);
        }
        if (hits.size() == 1) return hits.get(0);
        if (hits.isEmpty()) throw new ToolError("No class " + name + ". Search with classes.");
        throw new ToolError("Several classes are called " + name + ": " + String.join(", ", hits.subList(0, Math.min(15, hits.size()))));
    }

    // ---------------------------------------------------------------- readable signatures

    private static final Pattern PKG = Pattern.compile("\\b(?:[a-z][a-z0-9_]*\\.)+(?=[A-Z])");

    /** java.util.List<net.minecraft.world.entity.Entity> -> List<Entity>; nested Outer$Inner -> Outer.Inner. */
    static String shortType(Type t) {
        return shortName(t.getTypeName());
    }

    static String shortName(String s) {
        return PKG.matcher(s).replaceAll("").replace('$', '.');
    }

    private static String mods(int m) {
        String s = Modifier.toString(m & (Modifier.PUBLIC | Modifier.PROTECTED | Modifier.PRIVATE | Modifier.STATIC | Modifier.FINAL | Modifier.ABSTRACT));
        return s.isEmpty() ? "" : s + " ";
    }

    private static String params(Type[] ps, boolean varargs) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ps.length; i++) {
            if (i > 0) sb.append(", ");
            String t = shortType(ps[i]);
            if (varargs && i == ps.length - 1 && t.endsWith("[]")) t = t.substring(0, t.length() - 2) + "...";
            sb.append(t);
        }
        return sb.toString();
    }

    private static String describe(Method m) {
        String tp = m.getTypeParameters().length == 0 ? "" : "<" + String.join(", ", Arrays.stream(m.getTypeParameters()).map(CodeTools::shortType).toList()) + "> ";
        return mods(m.getModifiers()) + tp + shortType(m.getGenericReturnType()) + " " + m.getName() + "(" + params(m.getGenericParameterTypes(), m.isVarArgs()) + ")";
    }

    private static String describe(Constructor<?> c) {
        return mods(c.getModifiers()) + shortName(c.getDeclaringClass().getName()) + "(" + params(c.getGenericParameterTypes(), c.isVarArgs()) + ")";
    }

    private static String describe(Field f) {
        String s = mods(f.getModifiers()) + shortType(f.getGenericType()) + " " + f.getName();
        // small constants are worth seeing (ids, sizes, keys)
        if (Modifier.isStatic(f.getModifiers()) && Modifier.isFinal(f.getModifiers())
                && (f.getType().isPrimitive() || f.getType() == String.class)) {
            try {
                f.setAccessible(true);
                Object v = f.get(null);
                s += " = " + (v instanceof String str ? "\"" + str + "\"" : v);
            } catch (Throwable ignored) {
            }
        }
        return s;
    }

    private static String kind(Class<?> c) {
        if (c.isAnnotation()) return "annotation";
        if (c.isInterface()) return "interface";
        if (c.isEnum()) return "enum";
        if (c.isRecord()) return "record";
        return Modifier.isAbstract(c.getModifiers()) ? "abstract class" : "class";
    }

    // ---------------------------------------------------------------- decompiling

    private static final Map<String, String> SOURCES = Collections.synchronizedMap(new LinkedHashMap<>(32, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> e) {
            return size() > 24;
        }
    });

    private static byte[] classBytes(String internalName) throws IOException {
        try (InputStream in = LOADER.getResourceAsStream(internalName + ".class")) {
            return in == null ? null : in.readAllBytes();
        }
    }

    /** The decompiled source of a top-level class (with its nested classes), cached. */
    static String source(String binaryName) throws Exception {
        String top = binaryName.contains("$") ? binaryName.substring(0, binaryName.indexOf('$')) : binaryName;
        String cached = SOURCES.get(top);
        if (cached != null) return cached;
        String internal = top.replace('.', '/');
        Map<String, byte[]> classes = new LinkedHashMap<>();
        byte[] main = classBytes(internal);
        if (main == null) throw new ToolError("The class file of " + top + " can't be read (a JDK class, or generated at runtime).");
        classes.put(internal, main);
        for (String n : index().keySet()) {
            if (n.startsWith(top + "$")) {
                byte[] b = classBytes(n.replace('.', '/'));
                if (b != null) classes.put(n.replace('.', '/'), b);
            }
        }
        String[] result = new String[1];
        IContextSource src = new IContextSource() {
            @Override
            public String getName() {
                return "mcp-link";
            }

            @Override
            public Entries getEntries() {
                List<Entry> list = new ArrayList<>();
                for (String n : classes.keySet()) list.add(Entry.atBase(n));
                return new Entries(list, List.of(), List.of());
            }

            @Override
            public InputStream getInputStream(String resource) {
                String n = resource.endsWith(CLASS_SUFFIX) ? resource.substring(0, resource.length() - CLASS_SUFFIX.length()) : resource;
                byte[] b = classes.get(n);
                return b == null ? null : new ByteArrayInputStream(b);
            }

            @Override
            public IOutputSink createOutputSink(IResultSaver saver) {
                return new IOutputSink() {
                    @Override
                    public void begin() {
                    }

                    @Override
                    public void acceptClass(String qualifiedName, String fileName, String content, int[] mapping) {
                        if (qualifiedName.equals(internal) || result[0] == null) result[0] = content;
                    }

                    @Override
                    public void acceptDirectory(String directory) {
                    }

                    @Override
                    public void acceptOther(String path) {
                    }

                    @Override
                    public void close() {
                    }
                };
            }
        };
        // every other class is read lazily from the game's class loader, so types, generics and imports come out right
        IContextSource libraries = new IContextSource() {
            @Override
            public String getName() {
                return "classpath";
            }

            @Override
            public Entries getEntries() {
                return Entries.EMPTY;
            }

            @Override
            public boolean isLazy() {
                return true;
            }

            @Override
            public boolean hasClass(String name) throws IOException {
                return LOADER.getResource(name + CLASS_SUFFIX) != null;
            }

            @Override
            public byte[] getClassBytes(String name) throws IOException {
                return classBytes(name);
            }

            @Override
            public InputStream getInputStream(String resource) {
                return LOADER.getResourceAsStream(resource);
            }
        };
        Map<String, Object> opts = new HashMap<>();
        opts.put(IFernflowerPreferences.THREADS, "1");
        opts.put(IFernflowerPreferences.INDENT_STRING, "    ");
        opts.put(IFernflowerPreferences.DECOMPILE_GENERIC_SIGNATURES, "1");
        opts.put(IFernflowerPreferences.REMOVE_SYNTHETIC, "1");
        opts.put(IFernflowerPreferences.REMOVE_BRIDGE, "1");
        opts.put(IFernflowerPreferences.MAX_PROCESSING_METHOD, "15");
        opts.put(IFernflowerPreferences.INCLUDE_ENTIRE_CLASSPATH, "0");
        opts.put(IFernflowerPreferences.NEW_LINE_SEPARATOR, "1");   // \n, not the platform's \r\n
        IResultSaver none = new IResultSaver() {
            public void saveFolder(String path) {
            }

            public void copyFile(String source, String path, String entryName) {
            }

            public void saveClassFile(String path, String qualifiedName, String entryName, String content, int[] mapping) {
            }

            public void createArchive(String path, String archiveName, Manifest manifest) {
            }

            public void saveDirEntry(String path, String archiveName, String entryName) {
            }

            public void copyEntry(String source, String path, String archiveName, String entry) {
            }

            public void saveClassEntry(String path, String archiveName, String qualifiedName, String entryName, String content) {
            }

            public void closeArchive(String path, String archiveName) {
            }
        };
        Thread t = Thread.currentThread();
        ClassLoader before = t.getContextClassLoader();
        t.setContextClassLoader(LOADER);
        try {
            BaseDecompiler d = new BaseDecompiler(none, opts, IFernflowerLogger.NO_OP);
            d.addSource(src);
            d.addLibrary(libraries);
            d.decompileContext();
        } finally {
            t.setContextClassLoader(before);
        }
        if (result[0] == null) throw new ToolError("The decompiler gave no output for " + top + ".");
        String text = result[0].replace("\r\n", "\n");
        SOURCES.put(top, text);
        return text;
    }

    // ---------------------------------------------------------------- tools

    public static void register(ToolRegistry t) {
        t.add("classes", "Search the classes of the game, its libraries and every installed mod by name (case-insensitive regex on the "
                        + "full name, e.g. 'Goat', 'entity\\.Display', 'enderscape.*Vault'). Use it to find the real 26.3 names before "
                        + "writing a script or reading code: many names changed in 26.x (Identifier, EntityTypes, GuiGraphicsExtractor...).",
                Schema.of().strReq("query", "Regex on the full class name").str("from", "Only classes from this mod id or library (e.g. minecraft, enderscape)")
                        .bool("nested", "Include nested classes (Outer$Inner; default true)").integer("limit", "Max results (default 60)"),
                a -> Game.heavy(() -> {
                    Pattern q = Pattern.compile(a.s("query"), Pattern.CASE_INSENSITIVE);
                    String from = a.s("from", null);
                    boolean nested = a.b("nested", true);
                    int limit = Math.max(1, Math.min(500, a.i("limit", 60)));
                    JsonArray arr = new JsonArray();
                    int total = 0;
                    for (var e : index().entrySet()) {
                        String n = e.getKey();
                        if (!nested && n.contains("$")) continue;
                        if (n.matches(".*\\$\\d+(\\$.*)?")) continue;   // anonymous classes
                        if (from != null && !e.getValue().equalsIgnoreCase(from)) continue;
                        if (!q.matcher(n).find()) continue;
                        if (total++ < limit) arr.add(J.obj().put("name", n).put("from", e.getValue()).build());
                    }
                    return J.obj().put("total", total).put("classes", arr).put("indexed", index().size()).build();
                }));

        t.add("class_info", "A class's members with full signatures: kind, parents and interfaces, nested classes, constructors, fields "
                        + "(with values of small constants) and methods, including private ones. Names can be full (a.b.Outer$Inner or "
                        + "a.b.Outer.Inner) or simple when unique. Parameter names are not in signatures; read them with source.",
                Schema.of().strReq("name", "Class name").str("member", "Regex: only members whose name matches")
                        .bool("inherited", "Also public members inherited from parents (default false)"),
                a -> Game.heavy(() -> {
                    String name = resolveName(a.s("name"));
                    Class<?> c = Class.forName(name, false, LOADER);
                    Pattern mp = a.has("member") ? Pattern.compile(a.s("member"), Pattern.CASE_INSENSITIVE) : null;
                    boolean inherited = a.b("inherited", false);
                    J j = J.obj().put("name", name).put("kind", kind(c)).put("modifiers", Modifier.toString(c.getModifiers()))
                            .put("from", index().getOrDefault(name, "?"));
                    List<String> parents = new ArrayList<>();
                    for (Class<?> s = c.getSuperclass(); s != null && s != Object.class; s = s.getSuperclass()) parents.add(s.getName());
                    j.opt("extends", parents);
                    j.opt("implements", Arrays.stream(c.getGenericInterfaces()).map(CodeTools::shortType).toList());
                    j.opt("nested", Arrays.stream(c.getDeclaredClasses()).map(Class::getName).sorted().toList());
                    if (c.getEnclosingClass() != null) j.put("inside", c.getEnclosingClass().getName());
                    if (c.isEnum()) j.put("constants", Arrays.stream(c.getEnumConstants()).map(String::valueOf).toList());
                    List<String> ctors = new ArrayList<>(), fields = new ArrayList<>(), methods = new ArrayList<>();
                    for (Constructor<?> k : c.getDeclaredConstructors()) {
                        if (!k.isSynthetic()) ctors.add(describe(k));
                    }
                    Map<String, Field> fs = new LinkedHashMap<>();
                    for (Field f : c.getDeclaredFields()) if (!f.isSynthetic()) fs.put(f.getName(), f);
                    if (inherited) for (Field f : c.getFields()) fs.putIfAbsent(f.getName(), f);
                    for (Field f : fs.values()) {
                        if (mp == null || mp.matcher(f.getName()).find()) {
                            String d = describe(f);
                            fields.add(f.getDeclaringClass() == c ? d : d + "   (from " + shortName(f.getDeclaringClass().getName()) + ")");
                        }
                    }
                    Map<String, Method> ms = new LinkedHashMap<>();
                    for (Method m : c.getDeclaredMethods()) if (!m.isSynthetic() && !m.isBridge()) ms.put(m.getName() + Arrays.toString(m.getParameterTypes()), m);
                    if (inherited) {
                        for (Method m : c.getMethods()) {
                            if (m.getDeclaringClass() != Object.class && !m.isBridge()) ms.putIfAbsent(m.getName() + Arrays.toString(m.getParameterTypes()), m);
                        }
                    }
                    List<Method> sorted = new ArrayList<>(ms.values());
                    sorted.sort((x, y) -> x.getName().compareTo(y.getName()));
                    for (Method m : sorted) {
                        if (mp != null && !mp.matcher(m.getName()).find()) continue;
                        String d = describe(m);
                        methods.add(m.getDeclaringClass() == c ? d : d + "   (from " + shortName(m.getDeclaringClass().getName()) + ")");
                    }
                    int cap = 600;
                    j.opt("constructors", mp == null ? ctors : List.of());
                    j.put("fields", fields.size() > cap ? fields.subList(0, cap) : fields);
                    j.put("methods", methods.size() > cap ? methods.subList(0, cap) : methods);
                    if (fields.size() > cap || methods.size() > cap) j.put("note", "Lists cut at " + cap + "; narrow with 'member'.");
                    return j.build();
                }));

        t.add("source", "The Java source of a class from the running game or a mod, decompiled on demand (real names and parameter "
                        + "names; the class as shipped, before mixins). Long classes come back as an outline of their members with line "
                        + "numbers: then ask for 'lines' or 'find'. Good for checking how something really works before relying on it.",
                Schema.of().strReq("name", "Class name (full, or simple when unique)").str("find", "Regex: only lines matching it, with context")
                        .integer("context", "Lines of context around 'find' hits (default 3)").str("lines", "Line range, e.g. 120-220")
                        .bool("outline", "Only the member outline (default: whole source when under 400 lines, else the outline)"),
                a -> Game.heavy(() -> {
                    String name = resolveName(a.s("name"));
                    String src = source(name);
                    String[] lines = TextView.lines(src);
                    J j = J.obj().put("class", name).put("total_lines", lines.length);
                    J ranged = TextView.range(j, lines, a);
                    if (ranged != null) return ranged.build();
                    boolean outline = a.b("outline", lines.length > 400);
                    if (!outline) return j.put("text", TextView.numbered(lines, 1, lines.length)).build();
                    return j.put("outline", TextView.outline(lines)).put("note", "Member outline with line numbers; read code with 'lines' (e.g. \"120-200\") or 'find'.").build();
                }));
    }
}
