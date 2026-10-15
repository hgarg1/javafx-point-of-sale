import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.List;
import java.util.jar.*;
import java.util.zip.ZipFile;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import javax.xml.parsers.DocumentBuilderFactory;

/** Shared source-file build driver; invoked by both native build scripts. */
class Package {
    final Path repo = Path.of("").toAbsolutePath().normalize();
    final Properties config = new Properties();
    final String os = System.getProperty("os.name").startsWith("Windows") ? "windows"
            : System.getProperty("os.name").startsWith("Mac") ? "macos" : "linux";
    final String arch = Set.of("aarch64", "arm64").contains(System.getProperty("os.arch")) ? "arm64" : "x64";
    Path fx, output, stage, jdk = Path.of(System.getProperty("java.home"));
    String version, variant = "both", modules;
    boolean imageOnly, check;

    public static void main(String[] args) {
        try { new Package().build(args); }
        catch (Exception error) { System.err.println("Packaging failed: " + error.getMessage()); System.exit(1); }
    }

    void build(String[] args) throws Exception {
        try (var input = Files.newInputStream(repo.resolve("packaging/versions.properties"))) { config.load(input); }
        Map<String,String> options = new HashMap<>();
        for (int index = 0; index < args.length; index++) {
            String key = args[index];
            if (key.equals("--image-only")) imageOnly = true;
            else if (key.equals("--check")) check = true;
            else if (Set.of("--version", "--variant", "--output", "--javafx-jmods").contains(key) && index + 1 < args.length)
                options.put(key, args[++index]);
            else throw new IllegalArgumentException("Unknown or incomplete option: " + key);
        }
        version = options.get("--version");
        if (version == null || !version.matches("[1-9][0-9]*\\.[0-9]+\\.[0-9]+"))
            throw new IllegalArgumentException("Supply --version MAJOR.MINOR.PATCH, for example 1.0.0");
        String[] parts = version.split("\\.");
        if (Integer.parseInt(parts[0]) > 255 || Integer.parseInt(parts[1]) > 255 || Integer.parseInt(parts[2]) > 65535)
            throw new IllegalArgumentException("Version exceeds Windows installer limits (255.255.65535)");
        variant = options.getOrDefault("--variant", "both");
        if (!Set.of("app", "demo", "both").contains(variant)) throw new IllegalArgumentException("Variant must be app, demo or both");
        output = repo.resolve(options.getOrDefault("--output", "target/installer")).normalize();
        Path allowed = repo.resolve("target/installer");
        if (!output.startsWith(allowed)) throw new IllegalArgumentException("Output must be under " + allowed);
        String fxPath = options.getOrDefault("--javafx-jmods", System.getenv("JAVAFX_JMODS"));
        if (fxPath == null || fxPath.isBlank()) throw new IllegalArgumentException("Supply --javafx-jmods or JAVAFX_JMODS (JavaFX " + value("javafx.version") + ")");
        fx = Path.of(fxPath).toAbsolutePath().normalize();
        preflight();
        if (check) { System.out.println("Prerequisites passed: " + os + " " + arch); return; }
        stage = output.resolve("staging/" + os + "-" + arch + "-" + version + "-" + System.currentTimeMillis());
        Files.createDirectories(stage);
        Path deps = stage.resolve("dependencies");
        run(List.of(maven(), "-B", "verify", "org.apache.maven.plugins:maven-dependency-plugin:"
                + value("dependency.plugin.version") + ":copy-dependencies", "-DincludeScope=runtime", "-DoutputDirectory=" + deps));
        Path appJar = find(repo.resolve("target"), "javafx-app-*.jar");
        Path h2 = deps.resolve("h2-" + value("h2.version") + ".jar");
        if (!Files.isRegularFile(h2)) throw new IOException("Missing pinned H2 jar: " + h2);
        Path payload = stage.resolve("payload"); Files.createDirectories(payload);
        // The modular development jar runs on the classpath. Strip its descriptor for jdeps analysis only.
        Path analysis = stage.resolve("analysis.jar");
        try (JarFile input = new JarFile(appJar.toFile()); JarOutputStream out = new JarOutputStream(Files.newOutputStream(analysis))) {
            var entries = input.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                if (entry.getName().equals("module-info.class")) continue;
                out.putNextEntry(new JarEntry(entry.getName()));
                try (var data = input.getInputStream(entry)) { data.transferTo(out); } out.closeEntry();
            }
        }
        String dependencies = capture(List.of(tool("jdeps"), "--multi-release", "26", "--ignore-missing-deps",
                "--print-module-deps", "--class-path", deps + File.separator + "*", analysis.toString(), h2.toString())).trim();
        Set<String> required = new TreeSet<>();
        for (String module : dependencies.split(",")) {
            if (module.startsWith("java.") || module.startsWith("jdk.")) required.add(module.trim());
        }
        if (!required.contains("java.sql")) throw new IOException("jdeps did not resolve the database runtime: " + dependencies);
        required.addAll(List.of("javafx.controls", "jdk.crypto.ec", "jdk.charsets", "jdk.localedata"));
        modules = String.join(",", required);
        Path runtime = stage.resolve("runtime");
        run(List.of(tool("jlink"), "--module-path", jdk.resolve("jmods") + File.pathSeparator + fx,
                "--add-modules", modules, "--strip-debug", "--no-header-files", "--no-man-pages", "--output", runtime.toString()));
        Files.copy(appJar, payload.resolve("point-of-sale.jar")); Files.copy(h2, payload.resolve(h2.getFileName()));
        Path notices = payload.resolve("licenses"); Files.createDirectories(notices);
        Files.copy(repo.resolve("THIRD-PARTY-NOTICES.md"), notices.resolve("THIRD-PARTY-NOTICES.md"));
        copyTree(repo.resolve("packaging/licenses"), notices);
        Files.writeString(notices.resolve("RUNTIME.txt"), "Eclipse Temurin " + value("jdk.version")
                + " and OpenJFX " + value("javafx.version") + " licences and notices are in the adjacent runtime/legal directory.\n");
        for (String type : variant.equals("both") ? List.of("app", "demo") : List.of(variant)) packageVariant(type, payload, runtime);
        System.out.println("Artifacts: " + output.resolve("artifacts"));
    }

    void preflight() throws Exception {
        if (!System.getProperty("java.version").equals(value("jdk.version")) || !System.getProperty("java.vendor").equals(value("jdk.vendor")))
            throw new IllegalStateException("Use Eclipse Temurin " + value("jdk.version") + " via JAVA_HOME; found "
                    + System.getProperty("java.vendor") + " " + System.getProperty("java.version"));
        if (!Set.of("amd64", "x86_64", "aarch64", "arm64").contains(System.getProperty("os.arch"))) throw new IllegalStateException("Unsupported CPU architecture");
        if (!os.equals("macos") && !arch.equals("x64")) throw new IllegalStateException("Windows and Linux v1 require x64");
        if (os.equals("macos") && Integer.parseInt(System.getProperty("os.version").split("\\.")[0]) < 14) throw new IllegalStateException("macOS 14+ required");
        for (String name : List.of("java", "jlink", "jdeps", "jpackage", "jmod"))
            if (!Files.isRegularFile(Path.of(tool(name)))) throw new IllegalStateException("Missing JDK tool " + name);
        String fxVersion = capture(List.of(tool("jmod"), "describe", fx.resolve("javafx.controls.jmod").toString()));
        if (!fxVersion.startsWith("javafx.controls@" + value("javafx.version") + "\n") && !fxVersion.startsWith("javafx.controls@" + value("javafx.version") + "\r"))
            throw new IllegalStateException("Expected JavaFX JMODs " + value("javafx.version"));
        verifyNatives();
        if (!capture(List.of(maven(), "--version")).contains("Apache Maven " + value("maven.version"))) throw new IllegalStateException("Maven " + value("maven.version") + " required");
        var xml = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(repo.resolve("pom.xml").toFile());
        for (String key : List.of("javafx.version", "h2.version"))
            if (!xml.getElementsByTagName(key).item(0).getTextContent().equals(value(key))) throw new IllegalStateException("pom.xml and packaging config disagree: " + key);
        if (!imageOnly) {
            if (os.equals("windows")) {
                if (!capture(List.of("wix", "--version")).trim().startsWith(value("wix.version")))
                    throw new IllegalStateException("WiX " + value("wix.version") + " must be on PATH");
                String extensions = capture(List.of("wix", "extension", "list", "-g"));
                for (String extension : List.of("WixToolset.Util.wixext", "WixToolset.UI.wixext"))
                    if (!extensions.contains(extension + " " + value("wix.version")))
                        throw new IllegalStateException("Install WiX extension with: wix extension add -g " + extension + "/" + value("wix.version"));
            }
            if (os.equals("linux")) { capture(List.of("dpkg-deb", "--version")); capture(List.of("fakeroot", "--version")); }
            if (os.equals("macos")) capture(List.of("hdiutil", "help"));
        }
    }

    void packageVariant(String type, Path payload, Path runtime) throws Exception {
        boolean demo = type.equals("demo");
        String name = value("app.name") + (demo ? " Demo" : "");
        String stem = "point-of-sale" + (demo ? "-demo" : "") + "-" + version + "-" + os + "-" + arch;
        Path root = stage.resolve(type); Files.createDirectories(root);
        Path icon = icon(root, demo);
        Path images = root.resolve("images");
        List<String> args = new ArrayList<>(List.of(tool("jpackage"), "--type", "app-image", "--name", name,
                "--app-version", version, "--vendor", value("vendor"), "--description", demo ? "Point of Sale demonstration with isolated sample data" : "Offline retail point of sale",
                "--input", payload.toString(), "--main-jar", "point-of-sale.jar", "--main-class", "com.example.app.Launcher",
                "--runtime-image", runtime.toString(), "--dest", images.toString(), "--icon", icon.toString(),
                "--java-options", "--add-modules=javafx.controls", "--java-options", "--enable-native-access=javafx.graphics"));
        if (demo) args.addAll(List.of("--java-options", "-Dpos.demo=true"));
        if (os.equals("macos")) args.addAll(List.of("--mac-package-identifier", value(type + ".id")));
        run(args);
        Path image = images.resolve(name + (os.equals("macos") ? ".app" : ""));
        Path bundledJava = os.equals("macos") ? image.resolve("Contents/runtime/Contents/Home/bin/java") : image.resolve("runtime/bin/java" + (os.equals("windows") ? ".exe" : ""));
        Path bundledApp = image.resolve(os.equals("macos") ? "Contents/app" : "app");
        List<String> smoke = new ArrayList<>(List.of(bundledJava.toString(), "--add-modules=javafx.controls", "--enable-native-access=javafx.graphics"));
        if (demo) smoke.add("-Dpos.demo=true");
        smoke.addAll(List.of("-cp", bundledApp + File.separator + "*", "com.example.app.Launcher", "--packaging-smoke"));
        run(smoke);
        Path artifacts = output.resolve("artifacts"); Files.createDirectories(artifacts);
        if (!imageOnly) {
            String format = os.equals("windows") ? "exe" : os.equals("macos") ? "dmg" : "deb";
            Path nativeOut = root.resolve("native");
            List<String> installer = new ArrayList<>(List.of(tool("jpackage"), "--verbose", "--type", format, "--app-image", image.toString(),
                    "--name", name, "--app-version", version, "--vendor", value("vendor"), "--dest", nativeOut.toString()));
            if (os.equals("windows")) installer.addAll(List.of("--win-per-user-install", "--win-menu", "--win-menu-group", "Point of Sale",
                    "--win-shortcut", "--win-shortcut-prompt", "--win-upgrade-uuid", value(type + ".upgrade.uuid"),
                    "--resource-dir", windowsResources(root).toString()));
            if (os.equals("macos")) installer.addAll(List.of("--mac-package-identifier", value(type + ".id")));
            if (os.equals("linux")) installer.addAll(List.of("--linux-package-name", demo ? "point-of-sale-demo" : "point-of-sale",
                    "--linux-shortcut", "--linux-app-category", "Office", "--install-dir", "/opt/" + (demo ? "point-of-sale-demo" : "point-of-sale"),
                    "--resource-dir", linuxResources(root, demo).toString()));
            run(installer);
            Path artifact = artifacts.resolve(stem + "." + format);
            Files.copy(find(nativeOut, "*." + format), artifact, StandardCopyOption.REPLACE_EXISTING);
            Files.writeString(artifacts.resolve(stem + "." + format + ".sha256"), sha256(artifact) + "  " + artifact.getFileName() + "\n");
        }
        Properties manifest = new Properties();
        manifest.putAll(config); manifest.setProperty("version", version); manifest.setProperty("variant", type);
        manifest.setProperty("os", os); manifest.setProperty("architecture", arch); manifest.setProperty("created", Instant.now().toString());
        manifest.setProperty("runtime.modules", modules); manifest.setProperty("image", image.toString());
        manifest.setProperty("verification", "in-memory runtime smoke passed; native install acceptance pending");
        manifest.setProperty("signed", "false");
        manifest.setProperty("application.sha256", sha256(payload.resolve("point-of-sale.jar")));
        manifest.setProperty("h2.sha256", sha256(payload.resolve("h2-" + value("h2.version") + ".jar")));
        try (var out = Files.newOutputStream(artifacts.resolve(stem + ".build.properties"))) { manifest.store(out, "Native package build manifest"); }
        System.out.println("Built " + name + ": " + image);
    }

    void verifyNatives() throws Exception {
        String suffix = os.equals("windows") ? "glass.dll" : os.equals("macos") ? "libglass.dylib" : "libglass.so";
        try (ZipFile zip = new ZipFile(fx.resolve("javafx.graphics.jmod").toFile())) {
            var nativeEntry = zip.stream().filter(entry -> entry.getName().endsWith("/" + suffix)).findFirst()
                    .orElseThrow(() -> new IOException("JavaFX JMODs do not contain " + os + " native libraries"));
            byte[] bytes;
            try (var input = zip.getInputStream(nativeEntry)) { bytes = input.readAllBytes(); }
            ByteBuffer binary = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            int machine = os.equals("windows") ? Short.toUnsignedInt(binary.getShort(binary.getInt(0x3c) + 4))
                    : os.equals("macos") ? binary.getInt(4) : Short.toUnsignedInt(binary.getShort(18));
            int expected = os.equals("windows") ? 0x8664 : os.equals("macos") ? (arch.equals("arm64") ? 0x100000c : 0x1000007) : 62;
            if (machine != expected) throw new IOException("JavaFX native architecture does not match " + arch);
        }
    }

    Path windowsResources(Path root) throws Exception {
        Path resources = root.resolve("resources"); Files.createDirectories(resources);
        String template = Files.readString(packagingTemplates().resolve("main.wxs"));
        String guard = """
            <Property Id="MSIRESTARTMANAGERCONTROL" Value="Disable" />
            <util:CloseApplication Id="DetectRunningPos" Target="$(var.JpAppName).exe"
                Property="POS_RUNNING" CloseMessage="no" EndSessionMessage="no"
                ElevatedCloseMessage="no" ElevatedEndSessionMessage="no" RebootPrompt="no" Timeout="1" />
            <CustomActionRef Id="Wix4CloseApplications_X64" />
            <CustomAction Id="BlockRunningPos" Error="Close $(var.JpAppName) before installing, upgrading or uninstalling. Your saved data will be kept." />
            <InstallExecuteSequence>
                <Custom Action="override Wix4CloseApplications_X64" Before="BlockRunningPos">1</Custom>
                <Custom Action="BlockRunningPos" Before="RemoveExistingProducts">POS_RUNNING</Custom>
            </InstallExecuteSequence>
            </Product>
            """;
        if (!template.contains("</Product>")) throw new IOException("Pinned jpackage template changed");
        String source = template.replace("</Product>", guard)
                .replace("http://schemas.microsoft.com/wix/UtilExtension", "http://wixtoolset.org/schemas/v4/wxs/util");
        // Custom resources are used verbatim by jpackage: explicitly convert to the pinned WiX 5 schema.
        var transformer = javax.xml.transform.TransformerFactory.newInstance().newTransformer(
                new javax.xml.transform.stream.StreamSource(packagingTemplates().resolve("wix3-to-wix4-conv.xsl").toUri().toString()));
        StringWriter converted = new StringWriter();
        transformer.transform(new javax.xml.transform.stream.StreamSource(new StringReader(source)),
                new javax.xml.transform.stream.StreamResult(converted));
        Files.writeString(resources.resolve("main.wxs"), converted.toString());
        Files.copy(repo.resolve("packaging/windows/overrides.wxi"), resources.resolve("overrides.wxi"));
        return resources;
    }

    Path linuxResources(Path root, boolean demo) throws Exception {
        Path resources = root.resolve("resources"); Files.createDirectories(resources);
        Path templates = packagingTemplates();
        String packageName = demo ? "point-of-sale-demo" : "point-of-sale";
        String guard = """
            # Refuse upgrades/removal while running; do not send signals or touch user data.
            for pos_exe in /proc/[0-9]*/exe; do
                pos_target=$(readlink "$pos_exe" 2>/dev/null || true)
                case "$pos_target" in
                    /opt/PACKAGE/bin/*)
                        echo 'Close Point of Sale before installing, upgrading or uninstalling.' >&2
                        exit 1 ;;
                esac
            done
            """.replace("PACKAGE", packageName);
        for (String action : List.of("preinstall", "prerm")) {
            String template = Files.readString(templates.resolve("template." + action));
            int firstLine = template.indexOf('\n');
            if (firstLine < 0 || !template.startsWith("#!")) throw new IOException("Pinned Debian maintainer script changed");
            Files.writeString(resources.resolve(action), template.substring(0, firstLine + 1) + guard + template.substring(firstLine + 1));
        }
        return resources;
    }

    Path packagingTemplates() {
        return FileSystems.getFileSystem(java.net.URI.create("jrt:/"))
                .getPath("/modules/jdk.jpackage/jdk/jpackage/internal/resources");
    }

    String value(String key) { return Objects.requireNonNull(config.getProperty(key), "Missing config " + key); }
    String tool(String name) { return jdk.resolve("bin/" + name + (os.equals("windows") ? ".exe" : "")).toString(); }
    String maven() { return os.equals("windows") ? "mvn.cmd" : "mvn"; }
    List<String> command(List<String> args) {
        if (os.equals("windows") && args.getFirst().endsWith(".cmd")) {
            List<String> wrapped = new ArrayList<>(List.of("cmd.exe", "/d", "/c")); wrapped.addAll(args); return wrapped;
        }
        return args;
    }
    void run(List<String> args) throws Exception {
        System.out.println("Running " + Path.of(args.getFirst()).getFileName());
        Process process = new ProcessBuilder(command(args)).directory(repo.toFile()).inheritIO().start();
        if (process.waitFor() != 0) throw new IOException("Command failed: " + args);
    }
    String capture(List<String> args) throws Exception {
        Process process = new ProcessBuilder(command(args)).directory(repo.toFile()).redirectErrorStream(true).start();
        String text = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.waitFor() != 0) throw new IOException("Command failed: " + args + "\n" + text); return text;
    }
    static Path find(Path dir, String glob) throws IOException {
        try (var files = Files.newDirectoryStream(dir, glob)) {
            List<Path> matches = new ArrayList<>(); files.forEach(matches::add);
            if (matches.size() != 1) throw new IOException("Expected exactly one " + glob + " under " + dir + "; found " + matches.size());
            return matches.getFirst();
        }
    }
    static void copyTree(Path from, Path to) throws IOException {
        try (Stream<Path> files = Files.walk(from)) {
            for (Path source : files.toList()) {
                Path dest = to.resolve(from.relativize(source));
                if (Files.isDirectory(source)) Files.createDirectories(dest); else Files.copy(source, dest);
            }
        }
    }
    static String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(file)) { byte[] bytes = new byte[65536]; int read;
            while ((read = input.read(bytes)) != -1) digest.update(bytes, 0, read); }
        return HexFormat.of().formatHex(digest.digest());
    }
    Path icon(Path dir, boolean demo) throws Exception {
        String extension = os.equals("windows") ? "ico" : os.equals("macos") ? "icns" : "png";
        Path output = dir.resolve("app." + extension);
        BufferedImage image = new BufferedImage(1024, 1024, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(Color.decode(demo ? "#b9cba7" : "#394b33")); g.fillRoundRect(16, 16, 992, 992, 224, 224);
        g.setColor(Color.decode(demo ? "#1c2817" : "#fffdf8")); g.setFont(new Font("SansSerif", Font.BOLD, 690));
        FontMetrics metrics = g.getFontMetrics(); g.drawString("P", (1024 - metrics.stringWidth("P")) / 2, 760);
        if (demo) { g.setFont(new Font("SansSerif", Font.BOLD, 92)); g.drawString("DEMO", 355, 925); } g.dispose();
        ByteArrayOutputStream png = new ByteArrayOutputStream(); ImageIO.write(image, "png", png);
        if (extension.equals("png")) Files.write(output, png.toByteArray());
        else if (extension.equals("icns")) try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(output))) {
            out.writeBytes("icns"); out.writeInt(png.size() + 16); out.writeBytes("ic10"); out.writeInt(png.size() + 8); png.writeTo(out);
        } else {
            BufferedImage small = new BufferedImage(256, 256, BufferedImage.TYPE_INT_ARGB); Graphics2D graphics = small.createGraphics();
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.drawImage(image, 0, 0, 256, 256, null); graphics.dispose(); png.reset(); ImageIO.write(small, "png", png);
            ByteBuffer header = ByteBuffer.allocate(22).order(ByteOrder.LITTLE_ENDIAN);
            header.putShort((short)0).putShort((short)1).putShort((short)1).putInt(0).putShort((short)1).putShort((short)32).putInt(png.size()).putInt(22);
            try (var out = Files.newOutputStream(output)) { out.write(header.array()); png.writeTo(out); }
        }
        return output;
    }
}
