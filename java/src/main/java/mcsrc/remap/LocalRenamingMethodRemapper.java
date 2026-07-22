package mcsrc.remap;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import javax.lang.model.SourceVersion;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.Attribute;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;
import org.objectweb.asm.TypePath;
import org.objectweb.asm.commons.MethodRemapper;
import org.objectweb.asm.tree.ParameterNode;

import speiger.src.collections.objects.maps.interfaces.Object2IntMap;
import speiger.src.collections.objects.maps.interfaces.Object2IntMap.BuilderCache;

public class LocalRenamingMethodRemapper extends MethodRemapper {
	private record LocalVariable(String name, String descriptor, String signature, Label start, Label end, int index) {
		public LocalVariable withName(String name) {
			return new LocalVariable(name, descriptor, signature, start, end, index);
		}
	}
	private static final String[] SINGLE_CHAR_STRINGS = {
            "a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k", "l", "m",
            "n", "o", "p", "q", "r", "s", "t", "u", "v", "w", "x", "y", "z"
    };
	private final List<ParameterNode> parameters = new ArrayList<>(5);
	private final List<LocalVariable> localVariables;
	private final String className, methodName, methodDesc;
	private final Type[] argTypes;
	private final boolean isAbstract, isStatic;
	private final Object2IntMap<String> localNameCounts = Object2IntMap.builder().map();
	private final BuilderCache<Label> labelOpIndexes = Object2IntMap.builder().start();
	private boolean writtenParameters;
	private Label start, end;
	private int opIndex;

	public LocalRenamingMethodRemapper(MethodVisitor methodVisitor, FullRemapper remapper,
			String className, String methodName, String methodDesc, int methodAccess) {
		super(RemapWorker.ASM_VERSION, methodVisitor, remapper);

		this.className = className;
		this.methodName = methodName;
		this.methodDesc = methodDesc;
		argTypes = Type.getArgumentTypes(methodDesc);
		isAbstract = Modifier.isAbstract(methodAccess);
		isStatic = Modifier.isStatic(methodAccess);
		localVariables = !isAbstract ? new ArrayList<>(5) : null;
	}

	@Override
	public void visitParameter(String name, int access) {
		parameters.add(new ParameterNode(name, access));
	}

	@Override
	public AnnotationVisitor visitAnnotationDefault() {
		writeParameters();
		return super.visitAnnotationDefault();
	}

	@Override
	public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
		writeParameters();
		return super.visitAnnotation(descriptor, visible);
	}

	@Override
	public AnnotationVisitor visitTypeAnnotation(int typeRef, TypePath typePath, String descriptor, boolean visible) {
		writeParameters();
		return super.visitTypeAnnotation(typeRef, typePath, descriptor, visible);
	}

	@Override
	public AnnotationVisitor visitParameterAnnotation(int parameter, String descriptor, boolean visible) {
		writeParameters();
		return super.visitParameterAnnotation(parameter, descriptor, visible);
	}

	@Override
	public void visitAttribute(Attribute attribute) {
		writeParameters();
		super.visitAttribute(attribute);
	}

	@Override
	public void visitCode() {
		writeParameters();
		super.visitCode();
	}

	private void writeParameters() {
		if (!writtenParameters) {
			writtenParameters = true;

			//Either the parameters match up, or there aren't any (in theory at least)
			if (parameters.size() == argTypes.length) {
				for (int i = 0; i < argTypes.length; i++) {
					ParameterNode parameter = parameters.get(i);

					String name = ((FullRemapper) remapper).mapMethodArg(className, methodName, methodDesc, getLvIndex(i), parameter.name);
					if (isValidLvName(name)) {
						localNameCounts.putIfAbsent(name, 1);
					} else {
						name = getNameFromType(remapper.mapDesc(argTypes[i].getDescriptor()), true);
					}
					parameter.name = name; //For later...

					super.visitParameter(name, parameter.access);
				}
			}

		}
	}

	@Override
	public void visitLabel(Label label) {
		labelOpIndexes.put(label, opIndex);
		if (start == null) start = label;
		end = label;
		super.visitLabel(label);
	}

	private void visitInstruction() {
		if (opIndex++ == 0 && start == null) {
			super.visitLabel(start = new Label());
		}
		end = null;
	}

	@Override
	public void visitInsn(int opcode) {
		visitInstruction();
		super.visitInsn(opcode);
	}

	@Override
	public void visitIntInsn(int opcode, int operand) {
		visitInstruction();
		super.visitIntInsn(opcode, operand);
	}

	@Override
	public void visitVarInsn(int opcode, int varIndex) {
		visitInstruction();
		super.visitVarInsn(opcode, varIndex);
	}

	@Override
	public void visitTypeInsn(int opcode, String type) {
		visitInstruction();
		super.visitTypeInsn(opcode, type);
	}

	@Override
	public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
		visitInstruction();
		super.visitFieldInsn(opcode, owner, name, descriptor);
	}

	@Override
	public void visitMethodInsn(int opcodeAndSource, String owner, String name, String descriptor, boolean isInterface) {
		visitInstruction();
		super.visitMethodInsn(opcodeAndSource, owner, name, descriptor, isInterface);
	}

	@Override
	public void visitInvokeDynamicInsn(String name, String descriptor, Handle bootstrapMethodHandle, Object... bootstrapMethodArguments) {
		visitInstruction();
		super.visitInvokeDynamicInsn(name, descriptor, bootstrapMethodHandle, bootstrapMethodArguments);
	}

	@Override
	public void visitJumpInsn(int opcode, Label label) {
		visitInstruction();
		super.visitJumpInsn(opcode, label);
	}

	@Override
	public void visitLdcInsn(Object value) {
		visitInstruction();
		super.visitLdcInsn(value);
	}

	@Override
	public void visitIincInsn(int varIndex, int increment) {
		visitInstruction();
		super.visitIincInsn(varIndex, increment);
	}

	@Override
	public void visitTableSwitchInsn(int min, int max, Label dflt, Label... labels) {
		visitInstruction();
		super.visitTableSwitchInsn(min, max, dflt, labels);
	}

	@Override
	public void visitLookupSwitchInsn(Label dflt, int[] keys, Label[] labels) {
		visitInstruction();
		super.visitLookupSwitchInsn(dflt, keys, labels);
	}

	@Override
	public void visitMultiANewArrayInsn(String descriptor, int numDimensions) {
		visitInstruction();
		super.visitMultiANewArrayInsn(descriptor, numDimensions);
	}

	//FrameNodes & LineNumberNodes don't have opcodes

	@Override
	public void visitLocalVariable(String name, String descriptor, String signature, Label start, Label end, int index) {
		localVariables.add(new LocalVariable(name, descriptor, signature, start, end, index));
	}

	@Override
	public void visitMaxs(int maxStack, int maxLocals) {
		if (end == null) {
			super.visitLabel(end = new Label());
		}

		boolean[] argPresent = new boolean[argTypes.length];
		int argLvSize = getLvIndex(argTypes.length);
		Object2IntMap<Label> labelOpIndexes = this.labelOpIndexes.map();

		for (int i = 0; i < localVariables.size(); i++) {
			LocalVariable lv = localVariables.get(i);

			String name;
			if (!isStatic && lv.index == 0) {
				name = "this";
			} else if (lv.index < argLvSize) {
				if (parameters.size() == argTypes.length) {//Already done the method args
					name = parameters.get(i).name;
				} else {//Need to do the method args
					name = ((FullRemapper) remapper).mapMethodArg(className, methodName, methodDesc, getLvIndex(i), lv.name);

					if (isValidLvName(name)) {
						localNameCounts.putIfAbsent(name, 1);
					} else {
						name = getNameFromType(remapper.mapDesc(argTypes[i].getDescriptor()), true);
					}
				}
				argPresent[getAsmIndex(lv.index)] = true;
			} else {
				int startOpIdx = labelOpIndexes.getInt(lv.start);
				name = ((FullRemapper) remapper).mapMethodVar(className, methodName, methodDesc, lv.index, startOpIdx, i, lv.name);

				if (isValidLvName(name)) {
					localNameCounts.putIfAbsent(name, 1);
				} else {
					name = getNameFromType(remapper.mapDesc(lv.descriptor), true);
				}
			}

			if (!name.equals(lv.name)) localVariables.set(i, lv.withName(name));
		}

		for (int i = 0; i < argTypes.length; i++) {
			if (!argPresent[i]) {
				String desc = argTypes[i].getDescriptor();
				String name = getNameFromType(remapper.mapDesc(desc), true);
				localVariables.add(new LocalVariable(name, desc, null, start, end, getLvIndex(i)));
			}
		}

		for (LocalVariable lv : localVariables) {
			super.visitLocalVariable(lv.name, lv.descriptor, lv.signature, lv.start, lv.end, lv.index);
		}

		super.visitMaxs(maxStack, maxLocals);
	}

	@Override
	public void visitEnd() {
		writeParameters();
		super.visitEnd();
	}

	private int getLvIndex(int asmIndex) {
		int out = 0;
		if (!isStatic) out++;

		for (int i = 0; i < asmIndex; i++) {
			out += argTypes[i].getSize();
		}

		return out;
	}

	private int getAsmIndex(int lvIndex) {
		if (!isStatic) lvIndex--;

		for (int i = 0; i < argTypes.length; i++) {
			if (lvIndex == 0) return i;
			lvIndex -= argTypes[i].getSize();
		}

		return -1;
	}

	private String getNameFromType(String type, boolean isArg) {
		boolean plural = false;
		if (type.charAt(0) == '[') {
			plural = true;
			type = type.substring(type.lastIndexOf('[') + 1);
		}

		boolean incrementLetter = true;
		String varName;
		switch (type.charAt(0)) {
	        case 'B' -> varName = "b";
	        case 'C' -> varName = "c";
	        case 'D' -> varName = "d";
	        case 'F' -> varName = "f";
	        case 'I' -> varName = "i";
	        case 'J' -> varName = "l";
	        case 'S' -> varName = "s";
	        case 'Z' -> {
	            varName = "bl";
	            incrementLetter = false;
	        }
	        case 'L' -> {
	            int start = type.lastIndexOf('/') + 1;
	            int startDollar = type.lastIndexOf('$') + 1;
	
	            if (startDollar > start && startDollar < type.length() - 1) {
	                start = startDollar;
	            } else if (start == 0) {
	                start = 1;
	            }
	
	            char first = type.charAt(start);
	            char firstLc = Character.toLowerCase(first);
	
	            if (first == firstLc) {
	                varName = null;
	            } else {
	                varName = firstLc + type.substring(start + 1, type.length() - 1);
	            }
	
	            if (!isValidJavaIdentifier(varName)) {
	                varName = isArg ? "arg" : "lv";
	            }
	
	            incrementLetter = false;
	        }
	        default -> throw new IllegalStateException("Unexpected descriptor: " + type);
	    }

		boolean hasPluralS = false;
		if (plural) {
			String pluralVarName = varName + 's';

			if (!isJavaKeyword(pluralVarName)) {
				varName = pluralVarName;
				hasPluralS = true;
			}
		}

		if (incrementLetter) {
			for (int index = -1; localNameCounts.putIfAbsent(varName, 1) != 0 || isJavaKeyword(varName);) {
				if (index < 0) index = getNameIndex(varName, hasPluralS);

				varName = getIndexName(++index, plural);
			}

			return varName;
		}

		String baseVarName = varName;
		int count = localNameCounts.computeInt(baseVarName, (name, nameCount) -> nameCount + 1);
		if (count == 1) {
			if (isJavaKeyword(baseVarName)) {
				varName += '_';
			} else {
				return varName;
			}
		} else {
			varName = baseVarName + count;
		}

		while (localNameCounts.putIfAbsent(varName, 1) != 0) {
			varName = baseVarName + count++;
		}

		localNameCounts.put(baseVarName, count);
		return varName;
	}

	private static int getNameIndex(String name, boolean plural) {
		int out = 0;

		for (int i = 0, max = name.length() - (plural ? 1 : 0); i < max; i++) {
			out = out * 26 + name.charAt(i) - 'a' + 1;
		}

		return out - 1;
	}

	private static String getIndexName(int index, boolean plural) {
		if (index < 26 && !plural) {
			return SINGLE_CHAR_STRINGS[index];
		} else {
			StringBuilder out = new StringBuilder(2);

			do {
				int next = index / 26;
				int cur = index - next * 26;
				out.append((char) ('a' + cur));
				index = next - 1;
			} while (index >= 0);

			out.reverse();

			if (plural) out.append('s');

			return out.toString();
		}
	}

	private static boolean isValidLvName(String name) {
		return isValidJavaIdentifier(name) && !isJavaKeyword(name); //Any additional name checks can be done in here
	}

	private static boolean isValidJavaIdentifier(String name) {
		//return name != null && SourceVersion.isIdentifier(name) && !name.codePoints().anyMatch(Character::isIdentifierIgnorable);
		if (name == null || name.isEmpty()) {
			return false;
		}

		int cp = name.codePointAt(0);
		if (!Character.isJavaIdentifierStart(cp)) {
			return false;
		}

		for (int i = Character.charCount(cp); i < name.length(); i += Character.charCount(cp)) {
			cp = name.codePointAt(i);

			if (!Character.isJavaIdentifierPart(cp)) {
				return false;
			}
		}

		return !name.codePoints().anyMatch(Character::isIdentifierIgnorable);
	}

	private static boolean isJavaKeyword(String name) {
		//return SourceVersion.isKeyword(name);
		switch (name) {
		case "strictfp":
		case "assert":
		case "enum":
		case "_":
		case "public":
		case "protected":
		case "private":
		case "abstract":
		case "static":
		case "final":
		case "transient":
		case "volatile":
		case "synchronized":
		case "native":
		case "class":
		case "interface":
		case "extends":
		case "package":
		case "throws":
		case "implements":
		case "boolean":
		case "byte":
		case "char":
		case "short":
		case "int":
		case "long":
		case "float":
		case "double":
		case "void":
		case "if":
		case "else":
		case "try":
		case "catch":
		case "finally":
		case "do":
		case "while":
		case "for":
		case "continue":
		case "switch":
		case "case":
		case "default":
		case "break":
		case "throw":
		case "return":
		case "this":
		case "new":
		case "super":
		case "import":
		case "instanceof":
		case "goto":
		case "const":
		case "null":
		case "true":
		case "false":
			return true;
		default:
			return false;
		}
	}
}