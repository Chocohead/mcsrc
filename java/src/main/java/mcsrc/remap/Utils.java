package mcsrc.remap;

import org.teavm.jso.JSBody;
import org.teavm.jso.JSFunctor;
import org.teavm.jso.JSObject;
import org.teavm.jso.core.JSArray;
import org.teavm.jso.core.JSMap;
import org.teavm.jso.core.JSObjects;
import org.teavm.jso.function.JSMapping;

public class Utils {
	public static boolean isNullish(JSObject obj) {
		return obj == null || JSObjects.isUndefined(obj);
	}

	@FunctionalInterface
	@JSFunctor
	public interface JSArrayFunction<T extends JSObject, R extends JSObject> extends JSObject {
		R apply(T value, int index, JSArray<T> array);
	}

	@JSBody(params = {"array", "callback"}, script = "return array.map(callback);")
	public native static <T extends JSObject, R extends JSObject> JSArray<R> map(JSArray<T> array, JSArrayFunction<T, R> callback);

	@JSBody(params = {"map", "transformer"}, script = "return new Map(map.entries().map(entry => [entry[0], transformer(entry[1])]));")
	public native static <K extends JSObject, VT extends JSObject, VR extends JSObject>
		JSMap<K, VR> mapValues(JSMap<K, VT> map, JSMapping<VT, VR> transformer);

	@FunctionalInterface
	@JSFunctor
	public interface JSArrayConsumer<T extends JSObject> extends JSObject {
		void accept(T value, int index, JSArray<T> array);
	}

	@JSBody(params = {"array", "action"}, script = "array.forEach(action);")
	public native static <T extends JSObject> void forEach(JSArray<T> map, JSArrayConsumer<T> action);

	@FunctionalInterface
	@JSFunctor
	public interface JSMapConsumer<K extends JSObject, V extends JSObject> extends JSObject {
		void accept(V value, K key, JSMap<K, V> map);
	}

	@JSBody(params = {"map", "action"}, script = "map.forEach(action);")
	public native static <K extends JSObject, V extends JSObject> void forEach(JSMap<K, V> map, JSMapConsumer<K, V> action);
}