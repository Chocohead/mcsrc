package mcsrc.remap;

import org.objectweb.asm.commons.Remapper;

public abstract class FullRemapper extends Remapper {
	public FullRemapper(int api) {
		super(api);
	}

	public abstract String mapMethodArg(String methodOwner, String methodName, String methodDesc, int lvIndex, String name);

	public abstract String mapMethodVar(String methodOwner, String methodName, String methodDesc, int lvIndex, int startOpIdx, int asmIndex, String name);
}