package obf.swag;

import org.objectweb.asm.*;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.SimpleRemapper;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import java.util.zip.ZipEntry;

public class JarObfuscator {
    private static final String CHARACTERS = "abcdefghijklmnopqrstuvwxyz0123456789";
    private static final int CLASS_NAME_LENGTH = 10;
    private static final int FIELD_NAME_LENGTH = 8;
    private static final int METHOD_NAME_LENGTH = 8;

    private String mainClassName = null;
    private String obfuscatedMainClassName = null;
    private boolean originalManifestPresent = false;
    private String rsrcMainClass = null;
    private String rsrcClassPath = null;
    private String obfuscatedRsrcMainClass = null;

    private final Random random = new Random();
    private final Map<String, String> classMappings = new HashMap<>();
    private final Map<String, String> fieldMappings = new HashMap<>();
    private final Map<String, String> methodMappings = new HashMap<>();
    private final Set<String> usedNames = new HashSet<>();

    public static void main(String[] args) {
        if (args.length != 1) {
            System.out.println("Использование: java -jar obfuscator.jar <путь_к_jar_файлу>");
            System.out.println("Пример: java -jar obfuscator.jar myapp.jar");
            return;
        }

        String jarPath = args[0];
        JarObfuscator obfuscator = new JarObfuscator();

        try {
            System.out.println("Начало обфускации: " + jarPath);
            obfuscator.obfuscateJar(jarPath);
            System.out.println("Обфускация завершена успешно!");
        } catch (Exception e) {
            System.err.println("Ошибка при обфускации: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public void obfuscateJar(String jarPath) throws IOException {
        Path originalPath = Paths.get(jarPath).toAbsolutePath();
        if (!Files.exists(originalPath)) {
            throw new FileNotFoundException("JAR файл не найден: " + jarPath);
        }

        Path tempDir = Files.createTempDirectory("jar_obfuscate");
        Path outputPath = getOutputPath(originalPath);

        try {
            System.out.println("Распаковка JAR...");
            extractJar(originalPath, tempDir);

            System.out.println("Чтение манифеста...");
            readOriginalManifest(tempDir);

            System.out.println("Сбор информации о классах...");
            collectClassInfo(tempDir);

            System.out.println("Генерация новых имен...");
            generateMappings();

            // Обновляем Rsrc-Main-Class после генерации маппингов
            updateRsrcMainClass();

            System.out.println("Обфускация байткода...");
            obfuscateAllClasses(tempDir);

            System.out.println("Создание нового JAR...");
            createJar(tempDir, outputPath);

            System.out.println("Результат сохранен в: " + outputPath);

        } finally {
            System.out.println("Очистка временных файлов...");
            deleteDirectory(tempDir);
        }
    }

    private void readOriginalManifest(Path tempDir) throws IOException {
        Path manifestPath = tempDir.resolve("META-INF/MANIFEST.MF");
        if (Files.exists(manifestPath)) {
            try (InputStream is = Files.newInputStream(manifestPath)) {
                Manifest manifest = new Manifest(is);
                Attributes attrs = manifest.getMainAttributes();

                rsrcMainClass = attrs.getValue("Rsrc-Main-Class");
                rsrcClassPath = attrs.getValue("Rsrc-Class-Path");

                if (rsrcMainClass != null) {
                    System.out.println("Найден Rsrc-Main-Class: " + rsrcMainClass);
                }

                if (rsrcClassPath != null) {
                    System.out.println("Найден Rsrc-Class-Path: " + rsrcClassPath);
                }
            }
        }
    }

    private void updateRsrcMainClass() {
        if (rsrcMainClass != null) {
            // Преобразуем в internal name для поиска в маппингах
            String rsrcMainClassInternal = rsrcMainClass.replace('.', '/');
            obfuscatedRsrcMainClass = classMappings.get(rsrcMainClassInternal);

            if (obfuscatedRsrcMainClass != null) {
                obfuscatedRsrcMainClass = obfuscatedRsrcMainClass.replace('/', '.');
                System.out.println("Обфусцированный Rsrc-Main-Class: " + obfuscatedRsrcMainClass);
            } else {
                // Если класс не найден в маппингах, оставляем оригинальное имя
                obfuscatedRsrcMainClass = rsrcMainClass;
                System.out.println("Rsrc-Main-Class не найден в маппингах, используется оригинальное имя");
            }
        }
    }

    private void collectClassInfo(Path tempDir) throws IOException {
        Files.walk(tempDir)
                .filter(path -> path.toString().endsWith(".class"))
                .forEach(path -> {
                    try {
                        analyzeClassFile(path);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
    }

    private void analyzeClassFile(Path classFile) throws IOException {
        byte[] classData = Files.readAllBytes(classFile);
        ClassReader classReader = new ClassReader(classData);

        ClassInfoCollector collector = new ClassInfoCollector();
        classReader.accept(collector, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

        String className = collector.getClassName();
        classMappings.putIfAbsent(className, null);

        // проверяем наличие метода main
        for (String method : collector.getMethods()) {
            if (method.startsWith("main([Ljava/lang/String;)V")) {
                mainClassName = className;
                System.out.println("Найден main метод в классе: " + className);
            }
        }

        // сохраняем поля и методы...
        for (String field : collector.getFields()) {
            String fieldKey = className + "." + field;
            fieldMappings.putIfAbsent(fieldKey, null);
        }

        for (String method : collector.getMethods()) {
            String methodKey = className + "." + method;
            methodMappings.putIfAbsent(methodKey, null);
        }
    }

    private void generateMappings() {
        // 1) Сначала установим для всех классов значение по умолчанию
        List<String> classNames = new ArrayList<>(classMappings.keySet());

        for (String className : classNames) {
            classMappings.put(className, className);
        }

        // 2) Сгенерируем новые имена для всех классов (кроме системных)
        for (String className : classNames) {
            if (shouldObfuscate(className)) {
                String newName = generateUniqueClassName();
                classMappings.put(className, newName);
                System.out.println("Класс " + className + " -> " + newName);
            }
        }

        // 3) Обработаем внутренние классы
        for (String className : classNames) {
            if (className.contains("$")) {
                int idx = className.indexOf('$');
                String outer = className.substring(0, idx);
                String suffix = className.substring(idx);
                String mappedOuter = classMappings.get(outer);
                if (mappedOuter != null && !mappedOuter.equals(outer)) {
                    String newInnerName = mappedOuter + suffix;
                    classMappings.put(className, newInnerName);
                    System.out.println("Внутренний класс " + className + " -> " + newInnerName);
                }
            }
        }

        // 4) Генерация имён для полей
        List<String> fieldKeys = new ArrayList<>(fieldMappings.keySet());
        for (String fieldKey : fieldKeys) {
            if (shouldObfuscateField(fieldKey)) {
                String newFieldName = generateUniqueFieldName();
                fieldMappings.put(fieldKey, newFieldName);
                System.out.println("Поле " + fieldKey + " -> " + newFieldName);
            } else {
                fieldMappings.put(fieldKey, fieldKey.substring(fieldKey.indexOf('.') + 1));
            }
        }

        // 5) Генерация имён для методов
        List<String> methodKeys = new ArrayList<>(methodMappings.keySet());
        for (String methodKey : methodKeys) {
            if (shouldObfuscateMethod(methodKey)) {
                String newMethodName = generateUniqueMethodName();
                methodMappings.put(methodKey, newMethodName);
                System.out.println("Метод " + methodKey + " -> " + newMethodName);
            } else {
                String part = methodKey.substring(methodKey.indexOf('.') + 1);
                methodMappings.put(methodKey, part);
            }
        }

        // 6) Обновляем main class name
        if (mainClassName != null) {
            obfuscatedMainClassName = classMappings.get(mainClassName);
            System.out.println("Main класс: " + mainClassName.replace('/', '.') +
                    " -> " + (obfuscatedMainClassName != null ?
                    obfuscatedMainClassName.replace('/', '.') : "не обфусцирован"));
        } else {
            System.out.println("Main-класс не найден среди классов.");
        }
    }

    private void obfuscateAllClasses(Path tempDir) throws IOException {
        List<Path> classFiles = Files.walk(tempDir)
                .filter(path -> path.toString().endsWith(".class"))
                .toList();

        System.out.println("Найдено классов для обфускации: " + classFiles.size());

        for (Path classFile : classFiles) {
            obfuscateSingleClass(classFile);
        }

        for (Path classFile : classFiles) {
            renameClassFile(tempDir, classFile);
        }
    }

    private void obfuscateSingleClass(Path classFile) throws IOException {
        byte[] originalData = Files.readAllBytes(classFile);
        byte[] obfuscatedData = obfuscateClassBytes(originalData);
        Files.write(classFile, obfuscatedData);
    }

    private byte[] obfuscateClassBytes(byte[] classData) {
        try {
            ClassReader classReader = new ClassReader(classData);
            ClassWriter classWriter = new ClassWriter(classReader, ClassWriter.COMPUTE_MAXS);

            Map<String, String> remapMappings = new HashMap<>();

            // Добавляем маппинг классов
            for (Map.Entry<String, String> entry : classMappings.entrySet()) {
                if (!entry.getKey().equals(entry.getValue())) {
                    remapMappings.put(entry.getKey(), entry.getValue());
                }
            }

            // Добавляем маппинг полей
            for (Map.Entry<String, String> entry : fieldMappings.entrySet()) {
                String[] parts = entry.getKey().split("\\.");
                if (parts.length == 2) {
                    String className = parts[0];
                    String fieldName = parts[1];
                    String newFieldName = entry.getValue();
                    String mappedClass = classMappings.getOrDefault(className, className);

                    if (!fieldName.equals(newFieldName)) {
                        remapMappings.put(className + "/" + fieldName, mappedClass + "/" + newFieldName);
                    }
                }
            }

            // Добавляем маппинг методов
            for (Map.Entry<String, String> entry : methodMappings.entrySet()) {
                String[] parts = entry.getKey().split("\\.");
                if (parts.length == 2) {
                    String className = parts[0];
                    String methodWithDesc = parts[1];
                    String newMethodName = entry.getValue();

                    // Извлекаем только имя метода (до '(')
                    int idx = methodWithDesc.indexOf('(');
                    String methodNameOnly = (idx != -1) ? methodWithDesc.substring(0, idx) : methodWithDesc;

                    String mappedClass = classMappings.getOrDefault(className, className);

                    if (!methodNameOnly.equals(newMethodName)) {
                        remapMappings.put(className + "/" + methodNameOnly, mappedClass + "/" + newMethodName);
                    }
                }
            }

            ClassVisitor remapper = new ClassRemapper(classWriter, new SimpleRemapper(remapMappings));
            classReader.accept(remapper, ClassReader.EXPAND_FRAMES);

            return classWriter.toByteArray();

        } catch (Exception e) {
            System.err.println("Ошибка при обфускации класса: " + e.getMessage());
            e.printStackTrace();
            return classData;
        }
    }

    private void renameClassFile(Path baseDir, Path classFile) throws IOException {
        String originalClassName = getClassName(baseDir, classFile);
        String obfuscatedClassName = classMappings.get(originalClassName);

        if (obfuscatedClassName != null && !obfuscatedClassName.equals(originalClassName)) {
            Path newPath = getClassPath(baseDir, obfuscatedClassName);
            Files.createDirectories(newPath.getParent());
            Files.move(classFile, newPath, StandardCopyOption.REPLACE_EXISTING);
            System.out.println("Переименован файл: " + originalClassName + " -> " + obfuscatedClassName);
        }
    }

    private boolean shouldObfuscate(String className) {
        // Не обфусцируем стандартные классы Java
        if (className.startsWith("java/") ||
                className.startsWith("javax/") ||
                className.startsWith("sun/") ||
                className.startsWith("com/sun/")) {
            return false;
        }

        // Не обфусцируем загрузчики ресурсов
        if (className.contains("RsrcLoader") || className.contains("cfg3wgjn5gc")) {
            return false;
        }

        return true;
    }

    private boolean shouldObfuscateField(String fieldKey) {
        return !fieldKey.contains("serialVersionUID") &&
                !fieldKey.contains("main") &&
                !fieldKey.contains("INSTANCE");
    }

    private boolean shouldObfuscateMethod(String methodKey) {
        return !methodKey.contains("main") &&
                !methodKey.contains("toString") &&
                !methodKey.contains("equals") &&
                !methodKey.contains("hashCode") &&
                !methodKey.contains("<init>") &&
                !methodKey.contains("<clinit>");
    }

    private String generateUniqueClassName() {
        String name;
        do {
            name = "c" + generateRandomString(CLASS_NAME_LENGTH);
        } while (usedNames.contains(name));
        usedNames.add(name);
        return name;
    }

    private String generateUniqueFieldName() {
        String name;
        do {
            name = "f" + generateRandomString(FIELD_NAME_LENGTH);
        } while (usedNames.contains(name));
        usedNames.add(name);
        return name;
    }

    private String generateUniqueMethodName() {
        String name;
        do {
            name = "m" + generateRandomString(METHOD_NAME_LENGTH);
        } while (usedNames.contains(name));
        usedNames.add(name);
        return name;
    }

    private String generateRandomString(int length) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < length; i++) {
            sb.append(CHARACTERS.charAt(random.nextInt(CHARACTERS.length())));
        }
        return sb.toString();
    }

    private String getClassName(Path baseDir, Path classFile) {
        String relativePath = baseDir.relativize(classFile).toString();
        return relativePath.substring(0, relativePath.length() - 6)
                .replace(File.separatorChar, '/');
    }

    private Path getClassPath(Path baseDir, String className) {
        return baseDir.resolve(className.replace('/', File.separatorChar) + ".class");
    }

    private void extractJar(Path jarPath, Path outputDir) throws IOException {
        try (JarFile jarFile = new JarFile(jarPath.toFile())) {
            Enumeration<JarEntry> entries = jarFile.entries();

            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                Path entryPath = outputDir.resolve(entry.getName());

                String entryNameNormalized = entry.getName().replace('\\', '/');
                if (entryNameNormalized.equalsIgnoreCase("META-INF/MANIFEST.MF")) {
                    originalManifestPresent = true;
                }

                if (entry.isDirectory()) {
                    Files.createDirectories(entryPath);
                } else {
                    Files.createDirectories(entryPath.getParent());
                    try (InputStream is = jarFile.getInputStream(entry)) {
                        Files.copy(is, entryPath, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
        }
    }

    private void createJar(Path sourceDir, Path outputPath) throws IOException {
        Manifest manifest = new Manifest();
        Attributes attrs = manifest.getMainAttributes();
        attrs.put(Attributes.Name.MANIFEST_VERSION, "1.0");

        // Основной Main-Class
        if (obfuscatedMainClassName != null) {
            attrs.put(Attributes.Name.MAIN_CLASS, obfuscatedMainClassName.replace('/', '.'));
            System.out.println("Установлен Main-Class: " + obfuscatedMainClassName.replace('/', '.'));
        }

        // Rsrc атрибуты
        if (obfuscatedRsrcMainClass != null) {
            attrs.put(new Attributes.Name("Rsrc-Main-Class"), obfuscatedRsrcMainClass);
            System.out.println("Установлен Rsrc-Main-Class: " + obfuscatedRsrcMainClass);
        } else if (rsrcMainClass != null) {
            attrs.put(new Attributes.Name("Rsrc-Main-Class"), rsrcMainClass);
            System.out.println("Установлен оригинальный Rsrc-Main-Class: " + rsrcMainClass);
        }

        if (rsrcClassPath != null) {
            attrs.put(new Attributes.Name("Rsrc-Class-Path"), rsrcClassPath);
            System.out.println("Установлен Rsrc-Class-Path: " + rsrcClassPath);
        }

        try (JarOutputStream jos = new JarOutputStream(
                new FileOutputStream(outputPath.toFile()), manifest)) {

            Files.walk(sourceDir)
                    .filter(path -> !Files.isDirectory(path))
                    .forEach(path -> {
                        try {
                            String entryName = sourceDir.relativize(path).toString()
                                    .replace(File.separatorChar, '/');
                            if (entryName.equalsIgnoreCase("META-INF/MANIFEST.MF")) {
                                return;
                            }

                            jos.putNextEntry(new JarEntry(entryName));
                            Files.copy(path, jos);
                            jos.closeEntry();
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    });
        }
    }

    private Path getOutputPath(Path originalPath) {
        String fileName = originalPath.getFileName().toString();
        String newFileName;
        if (fileName.toLowerCase().endsWith(".jar")) {
            newFileName = fileName.substring(0, fileName.length() - 4) + "_obfuscated.jar";
        } else {
            newFileName = fileName + "_obfuscated.jar";
        }

        Path parent = originalPath.getParent();
        if (parent == null) {
            parent = originalPath.toAbsolutePath().getParent();
            if (parent == null) {
                parent = Paths.get("").toAbsolutePath();
            }
        }
        return parent.resolve(newFileName);
    }

    private void deleteDirectory(Path path) throws IOException {
        Files.walk(path)
                .sorted(Comparator.reverseOrder())
                .map(Path::toFile)
                .forEach(File::delete);
    }

    private static class ClassInfoCollector extends ClassVisitor {
        private String className;
        private final Set<String> fields = new HashSet<>();
        private final Set<String> methods = new HashSet<>();

        public ClassInfoCollector() {
            super(Opcodes.ASM9);
        }

        @Override
        public void visit(int version, int access, String name, String signature,
                          String superName, String[] interfaces) {
            this.className = name;
            super.visit(version, access, name, signature, superName, interfaces);
        }

        @Override
        public FieldVisitor visitField(int access, String name, String descriptor,
                                       String signature, Object value) {
            fields.add(name);
            return super.visitField(access, name, descriptor, signature, value);
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor,
                                         String signature, String[] exceptions) {
            methods.add(name + descriptor);
            return super.visitMethod(access, name, descriptor, signature, exceptions);
        }

        public String getClassName() {
            return className;
        }

        public Set<String> getFields() {
            return fields;
        }

        public Set<String> getMethods() {
            return methods;
        }
    }
}

//ci7ud9t4yif <- start.class