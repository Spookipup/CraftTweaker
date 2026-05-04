package minetweaker.tasks;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputDirectory;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.TaskAction;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * Generates a native registrar class. Finds all classes with a @ZenClass or
 * @ZenExpansion annotation and generates a class with a single static
 * getClasses(List<Class>) method.  Overrides existing files if they exist.
 * (handy for having a stub in the original source)
 *
 * @author Stan Hebben
 */
public abstract class RegisterZenClassesTask extends DefaultTask {
	@InputDirectory
	public abstract DirectoryProperty getInputDir();

	@OutputDirectory
    @Optional
	public abstract DirectoryProperty getOutputDir();

	@Input
	public abstract Property<String> getClassName();

    public RegisterZenClassesTask() {
        getOutputDir().convention(getInputDir());
    }

	@TaskAction
	public void doTask() {
        final File inputDir = getInputDir().get().getAsFile();
        final File outputDir = getOutputDir().get().getAsFile();
        final String className = getClassName().get();

		List<RegisteredClass> registeredClasses = new ArrayList<>();
		List<OnRegisterMethod> onRegisterMethods = new ArrayList<>();
		iterate(inputDir, null, registeredClasses, onRegisterMethods);

		String fullClassName = className.replace('.', '/');

		// generate class
		ClassWriter output = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		output.visit(Opcodes.V1_6, Opcodes.ACC_PUBLIC, fullClassName, null, "java/lang/Object", null);

		MethodVisitor method = output.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "getClasses", "(Ljava/util/List;)V", null, null);
		method.visitCode();

		for (RegisteredClass registeredClass : registeredClasses) {
			Label skip = visitModOnlyGuard(method, registeredClass.modOnly);
			method.visitVarInsn(Opcodes.ALOAD, 0);
			method.visitLdcInsn(Type.getType("L" + registeredClass.className + ";"));
			method.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/List", "add", "(Ljava/lang/Object;)Z", true);
			method.visitInsn(Opcodes.POP);
			if (skip != null) {
				method.visitLabel(skip);
			}
		}

		for (OnRegisterMethod onRegisterMethod : onRegisterMethods) {
			Label skip = visitModOnlyGuard(method, onRegisterMethod.modOnly);
			method.visitMethodInsn(Opcodes.INVOKESTATIC, onRegisterMethod.className, onRegisterMethod.methodName, "()V", false);
			if (skip != null) {
				method.visitLabel(skip);
			}
		}

		method.visitInsn(Opcodes.RETURN);

		method.visitMaxs(0, 0);
		method.visitEnd();

		output.visitEnd();

		// write output file
		File outputFile = new File(outputDir, fullClassName + ".class");
		File outputFileDir = outputFile.getParentFile();
		if (!outputFileDir.exists()) {
			outputFileDir.mkdirs();
		}
		if (outputFile.exists()) {
			outputFile.delete();
		}

		try(FileOutputStream outputStream = new FileOutputStream(outputFile)) {
			outputStream.write(output.toByteArray());
		} catch (IOException ex) {
			Logger.getLogger(RegisterZenClassesTask.class.getName()).log(Level.SEVERE, null, ex);
		}
	}

	private Label visitModOnlyGuard(MethodVisitor method, List<ModOnlyDependency> modOnly) {
		if (modOnly.isEmpty()) {
			return null;
		}

		Label skip = new Label();
		for (ModOnlyDependency dependency : modOnly) {
			for (String mod : dependency.mods) {
				method.visitLdcInsn(mod);
				method.visitLdcInsn(dependency.version);
				method.visitMethodInsn(Opcodes.INVOKESTATIC, "minetweaker/util/ModOnlyHelper", "isModOnlyLoaded", "(Ljava/lang/String;Ljava/lang/String;)Z", false);
				method.visitJumpInsn(Opcodes.IFEQ, skip);
			}
		}

		return skip;
	}

	private void iterate(File dir, String pkg, List<RegisteredClass> registeredClasses, List<OnRegisterMethod> onRegisterMethods) {
		for (File f : dir.listFiles()) {
			if (f.isDirectory()) {
				if (pkg == null) {
					iterate(f, f.getName(), registeredClasses, onRegisterMethods);
				} else {
					iterate(f, pkg + "/" + f.getName(), registeredClasses, onRegisterMethods);
				}
			} else if (f.isFile()) {
				if (f.getName().endsWith(".class")) {
					processJavaClass(f, pkg, registeredClasses, onRegisterMethods);
				}
			}
		}
	}

	private void processJavaClass(File cls, String pkg, List<RegisteredClass> registeredClasses, List<OnRegisterMethod> onRegisterMethods) {
		try(InputStream input = new BufferedInputStream(new FileInputStream(cls))) {
            ClassReader reader = new ClassReader(input);

			AnnotationDetector detector = new AnnotationDetector();
			reader.accept(detector, ClassReader.SKIP_CODE);
			input.close();

			String className = pkg + "/" + cls.getName().substring(0, cls.getName().length() - 6);
			if (detector.isAnnotated) {
				registeredClasses.add(new RegisteredClass(className, detector.modOnly));
			}
			for (MethodAnnotationDetector onRegisterMethod : detector.onRegister) {
				onRegisterMethods.add(new OnRegisterMethod(
						className,
						onRegisterMethod.name,
						combineModOnly(detector.modOnly, onRegisterMethod.modOnly)));
			}
		} catch (IOException ex) {

		}
	}

	private List<ModOnlyDependency> combineModOnly(List<ModOnlyDependency> classMods, List<ModOnlyDependency> methodMods) {
		if (classMods.isEmpty()) {
			return methodMods;
		}
		if (methodMods.isEmpty()) {
			return classMods;
		}

		List<ModOnlyDependency> combined = new ArrayList<>(classMods);
		combined.addAll(methodMods);
		return combined;
	}

	private static class AnnotationDetector extends ClassVisitor {
		private boolean isAnnotated = false;
		private List<MethodAnnotationDetector> onRegister = new ArrayList<>();
		private List<ModOnlyDependency> modOnly = new ArrayList<>();

		public AnnotationDetector() {
			super(Opcodes.ASM5);
		}

		@Override
		public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
			if (desc.equals("Lstanhebben/zenscript/annotations/ZenExpansion;")) {
				isAnnotated = true;
			} else if (desc.equals("Lstanhebben/zenscript/annotations/ZenClass;")) {
				isAnnotated = true;
			} else if (desc.equals("Lminetweaker/annotations/BracketHandler;")) {
				isAnnotated = true;
			} else if (desc.equals("Lminetweaker/annotations/ModOnly;")) {
				return new ModOnlyAnnotationVisitor(modOnly);
			}

			return super.visitAnnotation(desc, visible);
		}

		@Override
		public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
			return new MethodAnnotationDetector(this, name, desc);
		}
	}

	private static class MethodAnnotationDetector extends MethodVisitor {
		private final AnnotationDetector detector;
		private final String name;
		private final String desc;
		private List<ModOnlyDependency> modOnly = new ArrayList<>();

		public MethodAnnotationDetector(AnnotationDetector detector, String name, String desc) {
			super(Opcodes.ASM4);

			this.detector = detector;
			this.name = name;
			this.desc = desc;
		}

		@Override
		public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
			if (desc.equals("Lminetweaker/annotations/OnRegister;")) {
				if (this.desc.equals("()V")) {
					detector.onRegister.add(this);
				} else {
					throw new RuntimeException("OnRegister annotation must be used on a static method without arguments or return value");
				}
			} else if (desc.equals("Lminetweaker/annotations/ModOnly;")) {
				return new ModOnlyAnnotationVisitor(modOnly);
			}

			return super.visitAnnotation(desc, visible);
		}
	}

	private static class OnRegisterMethod {
		private final String className;
		private final String methodName;
		private final List<ModOnlyDependency> modOnly;

		public OnRegisterMethod(String className, String methodName, List<ModOnlyDependency> modOnly) {
			this.className = className;
			this.methodName = methodName;
			this.modOnly = modOnly;
		}
	}

	private static class RegisteredClass {
		private final String className;
		private final List<ModOnlyDependency> modOnly;

		public RegisteredClass(String className, List<ModOnlyDependency> modOnly) {
			this.className = className;
			this.modOnly = modOnly;
		}
	}

	private static class ModOnlyAnnotationVisitor extends AnnotationVisitor {
		private final List<ModOnlyDependency> dependencies;
		private final List<String> mods = new ArrayList<>();
		private String version = "";

		public ModOnlyAnnotationVisitor(List<ModOnlyDependency> dependencies) {
			super(Opcodes.ASM5);

			this.dependencies = dependencies;
		}

		@Override
		public void visit(String name, Object value) {
			if ("value".equals(name) && value instanceof String) {
				mods.add((String) value);
			} else if ("version".equals(name) && value instanceof String) {
				version = (String) value;
			}

			super.visit(name, value);
		}

		@Override
		public AnnotationVisitor visitArray(String name) {
			if ("value".equals(name)) {
				return new AnnotationVisitor(Opcodes.ASM5) {

					@Override
					public void visit(String name, Object value) {
						if (value instanceof String) {
							mods.add((String) value);
						}

						super.visit(name, value);
					}
				};
			}

			return super.visitArray(name);
		}

		@Override
		public void visitEnd() {
			if (!mods.isEmpty()) {
				dependencies.add(new ModOnlyDependency(new ArrayList<>(mods), version));
			}
			super.visitEnd();
		}
	}

	private static class ModOnlyDependency {
		private final List<String> mods;
		private final String version;

		public ModOnlyDependency(List<String> mods, String version) {
			this.mods = mods;
			this.version = version;
		}
	}
}
