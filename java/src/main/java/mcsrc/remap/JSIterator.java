package mcsrc.remap;

import java.util.Iterator;

import org.teavm.jso.JSFunctor;
import org.teavm.jso.JSObject;
import org.teavm.jso.JSProperty;

public interface JSIterator<T extends JSObject> extends JSObject {
	public interface Result<T extends JSObject> extends JSObject {
		@JSProperty
		boolean isDone();

		@JSProperty
		T getValue();
	}

	Result<T> next();

	default Iterable<T> iter() {
		return () -> new Iterator<>() {
			private Result<T> it = JSIterator.this.next();

			@Override
			public boolean hasNext() {
				return !it.isDone();
			}

			@Override
			public T next() {
				T out = it.getValue();
				it = JSIterator.this.next();
				return out;
			}
		};
	}

	@FunctionalInterface
	@JSFunctor
	public interface FindPredicate<T extends JSObject> extends JSObject {
		boolean test(T element, int index);
	}

	T find(FindPredicate<T> callback);
}