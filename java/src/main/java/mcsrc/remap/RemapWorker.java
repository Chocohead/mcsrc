package mcsrc.remap;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;

import org.teavm.jso.JSExport;
import org.teavm.jso.core.JSArray;
import org.teavm.jso.core.JSMap;
import org.teavm.jso.core.JSNumber;
import org.teavm.jso.core.JSString;
import org.teavm.jso.typedarrays.ArrayBuffer;
import org.teavm.jso.typedarrays.Int8Array;

public class RemapWorker {
	static final int ASM_VERSION = Opcodes.ASM9;
	private static JSMap<JSString, RemappingClassInstance> classes = new JSMap<>();

	@JSExport
		public static IndexedClassInstance index2(ArrayBuffer arrayBuffer) {
				byte[] bytes = new Int8Array(arrayBuffer).copyToJavaArray();
				var visitor = new ClassVisitor(ASM_VERSION) {
				IndexedClassInstance instance;

				@Override
				public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
					JSArray<JSString> jsInterfaces = new JSArray<>(interfaces.length);

					for (int i = 0; i < interfaces.length; i++) {
						jsInterfaces.set(i, JSString.valueOf(interfaces[i]));
					}

					instance = IndexedClassInstance.create(name, superName, jsInterfaces, access);
				}

				@Override
				public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
					instance.getMembers().push(SimpleClassMember.create(false, name, descriptor, access));
					return null;
				}

				@Override
				public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
					instance.getMembers().push(SimpleClassMember.create(true, name, descriptor, access));
					return null;
				}
			};
				new ClassReader(bytes).accept(visitor, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
				return visitor.instance;
		}

	@JSExport
	public static void receiveClasses(JSMap<JSString, ClassInstance> classes) {
		RemapWorker.classes = Utils.mapValues(classes, RemappingClassInstance::create);
	}

	@JSExport
	public static Int8Array remapEntry2(ArrayBuffer entry) {
		byte[] classBytes = new Int8Array(entry).copyToJavaArray();
		ClassWriter writer = new ClassWriter(0) {
			@Override
			protected String getCommonSuperClass(String type1, String type2) {
				return "java/lang/Object";
			}
		};
		new ClassReader(classBytes).accept(new ClassRemapper(ASM_VERSION, writer, new FullRemapper(ASM_VERSION) {
			@Override
			public String map(String internalName) {
				ClassInstance cls = classes.get(JSString.valueOf(internalName));
				return !Utils.isNullish(cls) ? cls.getNewName() : internalName;
			}

			@Override
			public String mapFieldName(String owner, String name, String descriptor) {
				RemappingClassInstance cls = classes.get(JSString.valueOf(owner));
				if (Utils.isNullish(cls)) return name;

				ClassMember member = cls.resolve(false, MarshallingWorker.getFieldID(name, descriptor));
				if (Utils.isNullish(member)) return name;

				String newName = member.getNewName();
				return newName != null ? newName : name;
			}

			@Override
			public String mapRecordComponentName(String owner, String name, String descriptor) {
				return mapFieldName(owner, name, descriptor);
			}

			@Override
			public String mapMethodName(String owner, String name, String descriptor) {
				if (!descriptor.startsWith("(")) {//Apparently field Handles can end up here sometimes
					return mapFieldName(owner, name, descriptor);
				}

				RemappingClassInstance cls = classes.get(JSString.valueOf(owner));
				if (Utils.isNullish(cls)) return name;

				ClassMember member = cls.resolve(true, MarshallingWorker.getMethodID(name, descriptor));
				if (Utils.isNullish(member)) return name;

				String newName = member.getNewName();
				return newName != null ? newName : name;
			}

			@Override
			public String mapMethodArg(String methodOwner, String methodName, String methodDesc, int lvIndex, String name) {
				RemappingClassInstance cls = classes.get(JSString.valueOf(methodOwner));
				if (Utils.isNullish(cls)) return name;

				ClassMember member = cls.resolve(true, MarshallingWorker.getMethodID(methodName, methodDesc));
				if (Utils.isNullish(member)) return name;

				JSString newName = member.getArgs().get(JSNumber.valueOf(lvIndex));
				return !Utils.isNullish(newName) ? newName.stringValue() : name;
			}

			@Override
			public String mapMethodVar(String methodOwner, String methodName, String methodDesc,
					int lvIndex, int startOpIdx, int asmIndex, String name) {
				RemappingClassInstance cls = classes.get(JSString.valueOf(methodOwner));
				if (Utils.isNullish(cls)) return name;

				ClassMember member = cls.resolve(true, MarshallingWorker.getMethodID(methodName, methodDesc));
				if (Utils.isNullish(member)) return name;

				JSString newName = member.getVars().get(JSNumber.valueOf(lvIndex));
				return !Utils.isNullish(newName) ? newName.stringValue() : name;
			}
		}) {
			private String methodName, methodDesc;
			private int methodAccess;

			@Override
			public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
				methodName = name;
				methodDesc = descriptor;
				methodAccess = access;
				return super.visitMethod(access, name, descriptor, signature, exceptions);
			}

			@Override
			protected MethodVisitor createMethodRemapper(MethodVisitor methodVisitor) {
				return new LocalRenamingMethodRemapper(methodVisitor, (FullRemapper) remapper, className, methodName, methodDesc, methodAccess);
			}
		}, ClassReader.SKIP_FRAMES);

		byte[] remappedBytes = writer.toByteArray();
		var array = new Int8Array(remappedBytes.length);
		array.set(remappedBytes);
		return array;
	}

	@JSExport
		public static void clearRemapperState2() {
		classes = null;
	}
}