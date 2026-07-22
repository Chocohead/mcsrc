package mcsrc.remap;

import org.teavm.model.BasicBlock;
import org.teavm.model.ClassHolder;
import org.teavm.model.ClassHolderTransformer;
import org.teavm.model.ClassHolderTransformerContext;
import org.teavm.model.Instruction;
import org.teavm.model.MethodHolder;
import org.teavm.model.MethodReference;
import org.teavm.model.Program;
import org.teavm.model.ValueType;
import org.teavm.model.instructions.InvokeInstruction;
import org.teavm.vm.spi.TeaVMHost;
import org.teavm.vm.spi.TeaVMPlugin;

public class Plugin implements TeaVMPlugin {
	public static class SanityChecksSubstituter implements ClassHolderTransformer {
		private static final MethodReference OLD_METHOD = new MethodReference("speiger.src.collections.utils.SanityChecks",
				"checkArrayCapacity", ValueType.INTEGER, ValueType.INTEGER, ValueType.INTEGER, ValueType.VOID);
		private static final MethodReference NEW_METHOD = new MethodReference("java.util.Objects",
				"checkFromIndexSize", ValueType.INTEGER, ValueType.INTEGER, ValueType.INTEGER, ValueType.INTEGER);

		@Override
		public void transformClass(ClassHolder cls, ClassHolderTransformerContext context) {
			if ("speiger.src.collections.objects.maps.abstracts.AbstractObject2IntMap".equals(cls.getName())) {
				for (MethodHolder method : cls.getMethods()) {
					Program program = method.getProgram();
					if (program == null) continue; //Don't mind abstract methods

					for (BasicBlock block : program.getBasicBlocks()) {
						for (Instruction instruction : block) {
							if (instruction instanceof InvokeInstruction invoke) {
								if (OLD_METHOD.equals(invoke.getMethod())) {
									invoke.setMethod(NEW_METHOD); 
		                            invoke.setReceiver(null);
								}
							}
						}
					}
				}
			}
		}
	}

	@Override
	public void install(TeaVMHost host) {
		host.add(new SanityChecksSubstituter());
	}
}