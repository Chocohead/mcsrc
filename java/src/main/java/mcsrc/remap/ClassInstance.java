package mcsrc.remap;

import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

import org.teavm.jso.JSBody;
import org.teavm.jso.JSObject;
import org.teavm.jso.JSProperty;
import org.teavm.jso.core.JSArray;
import org.teavm.jso.core.JSMap;
import org.teavm.jso.core.JSString;

interface SimpleClassInstance extends JSObject {
	@JSProperty
	String getClassName();

	@JSProperty
	String getSuperName();

	@JSProperty
	JSArray<JSString> getInterfaces();

	@JSProperty
	int getAccessFlags();

	default boolean isInterface() {
		return Modifier.isInterface(getAccessFlags());
	}
}

interface IndexedClassInstance extends SimpleClassInstance {
	@JSBody(
		params = {"className", "superName", "interfaces", "accessFlags"}, 
		script = "return {className: className, superName: superName, interfaces: interfaces, accessFlags: accessFlags, members: []};"
	)
	static IndexedClassInstance create(String className, String superName, JSArray<JSString> interfaces, int accessFlags) {
		throw new UnsupportedOperationException();
	}

	@JSProperty
	JSArray<SimpleClassMember> getMembers();
}

public interface ClassInstance extends SimpleClassInstance {
	@JSBody(
		params = {"className", "superName", "interfaces", "accessFlags"}, 
		script = "return {className: className, superName: superName, interfaces: interfaces, accessFlags: accessFlags, parents: new Set(), children: new Set(), members: new Map(), newName: null};"
	)
	static ClassInstance create(String className, String superName, JSArray<JSString> interfaces, int accessFlags) {
		throw new UnsupportedOperationException();
	}

	@JSProperty
	JSSet<ClassInstance> getParents();

	@JSProperty
	JSSet<ClassInstance> getChildren();

	@JSProperty
	JSMap<JSString, ClassMember> getMembers();

	@JSProperty
	String getNewName();

	@JSProperty()
	void setNewName(String name);

	default ClassInstance getSuperClass() {
		return getParents().values().find((cls, index) -> !cls.isInterface());
	}

	default boolean isAssignableFrom(ClassInstance cls) {
		if (cls == this) return true;

		if (isInterface()) {
			Set<ClassInstance> visited = Collections.newSetFromMap(new IdentityHashMap<>());
			Deque<ClassInstance> queue = new ArrayDeque<>();
			visited.add(cls);

			do {
				for (ClassInstance parent : cls.getParents().values().iter()) {
					if (parent == this) return true;

					if (visited.add(parent)) {
						queue.addLast(parent);
					}
				}
			} while (!Utils.isNullish(cls = queue.pollFirst()));
		} else {
			do {
				cls = cls.getSuperClass();

				if (cls == this) return true;
			} while (!Utils.isNullish(cls));
		}

		return false;
	}
}

interface RemappingClassInstance extends ClassInstance {
	final ClassMember MISSING = ClassMember.create(null, true, null, null, 0);

	static RemappingClassInstance create(ClassInstance cls) {
		RemappingClassInstance out = (RemappingClassInstance) cls; //It's just a normal ClassInstance with another field
		out.setResolvedMembers(new JSMap<>());
		return out;
	}

	@JSProperty
	JSMap<JSString, ClassMember> getResolvedMembers();

	@JSProperty
	void setResolvedMembers(JSMap<JSString, ClassMember> members);

	default ClassMember resolve(boolean method, String id) {
		ClassMember member = getMembers().get(JSString.valueOf(id));
		if (!Utils.isNullish(member)) return member;

		member = getResolvedMembers().get(JSString.valueOf(id));
		if (Utils.isNullish(member)) {
			member = method ? resolveMethod(id) : resolveField(id);
			getResolvedMembers().set(JSString.valueOf(id), member);
		}

		return member != MISSING ? member : null;
	}

	private ClassMember resolveField(String id) {
		Deque<ClassInstance> queue = new ArrayDeque<>();
		Set<ClassInstance> visited = Collections.newSetFromMap(new IdentityHashMap<>());
		visited.add(this);

		for (ClassInstance context = this;;) {
			ClassInstance cls = context;

			do {
				for (ClassInstance parent : cls.getParents().values().iter()) {
					if (parent.isInterface() && visited.add(parent)) {
						ClassMember ret = parent.getMembers().get(JSString.valueOf(id));
						if (!Utils.isNullish(ret)) return ret;

						queue.addLast(parent);
					}
				}
			} while (!Utils.isNullish(cls = queue.pollLast()));

			cls = context;
			context = cls.getSuperClass();
			if (Utils.isNullish(context)) break;

			ClassMember parentMember = context.getMembers().get(JSString.valueOf(id));
			if (!Utils.isNullish(parentMember)) return parentMember;
		}

		return MISSING;
	}

	private ClassMember resolveMethod(String id) {
		for (ClassInstance cls = getSuperClass(); !Utils.isNullish(cls); cls = cls.getSuperClass()) {
			ClassMember parentMember = cls.getMembers().get(JSString.valueOf(id));
			if (!Utils.isNullish(parentMember)) return parentMember;
		}

		Deque<ClassInstance> queue = new ArrayDeque<>();
		Set<ClassInstance> visited = Collections.newSetFromMap(new IdentityHashMap<>());
		visited.add(this);
		List<ClassMember> matchedMethods = new ArrayList<>();
		boolean hasNonAbstract = false;
		ClassInstance cls = this;

		do {
			for (ClassInstance parent : cls.getParents().values().iter()) {
				if (!visited.add(parent)) continue;

				if (parent.isInterface()) {
					ClassMember parentMember = parent.getMembers().get(JSString.valueOf(id));

					if (!Utils.isNullish(parentMember) && parentMember.isVirtual()) {
						if (!parentMember.isAbstract()) hasNonAbstract = true;
						matchedMethods.add(parentMember);
						continue;
					}
				}

				queue.addLast(parent);
			}
		} while (!Utils.isNullish(cls = queue.pollFirst()));

		if (hasNonAbstract && matchedMethods.size() > 1) {
			on: for (ClassMember member : matchedMethods) {
				if (member.isAbstract()) continue;

				for (ClassMember method : matchedMethods) {
					if (method != member && member.getOwner().isAssignableFrom(method.getOwner())) {
						continue on;
					}
				}

				return member;
			}
		}

		if (!matchedMethods.isEmpty()) return matchedMethods.get(0);

		return MISSING;
	}
}