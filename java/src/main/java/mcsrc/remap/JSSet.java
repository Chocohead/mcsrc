package mcsrc.remap;

import org.teavm.jso.JSClass;
import org.teavm.jso.JSObject;
import org.teavm.jso.JSProperty;
import org.teavm.jso.function.JSConsumer;

@JSClass(name = "Set")
public class JSSet<T extends JSObject> implements JSObject {
	public JSSet() {
	}

	@JSProperty
	public native int getSize();

	public native JSSet<T> add(T value);

	public native boolean has(T value);

	public native boolean delete(T value);

	public native void clear();

	public native JSIterator<T> values();

	public native void forEach(JSConsumer<T> action);
}