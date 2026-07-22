package mcsrc.remap;

import java.lang.reflect.Modifier;

import org.teavm.jso.JSBody;
import org.teavm.jso.JSObject;
import org.teavm.jso.JSProperty;
import org.teavm.jso.core.JSMap;
import org.teavm.jso.core.JSNumber;
import org.teavm.jso.core.JSString;

interface SimpleClassMember extends JSObject {
	@JSBody(
		params = {"method", "name", "desc", "accessFlags"}, 
		script = "return {method: method, name: name, desc: desc, accessFlags: accessFlags};"
	)
	static SimpleClassMember create(boolean method, String name, String desc, int accessFlags) {
		throw new UnsupportedOperationException();
	}

	@JSProperty
	boolean isMethod();

	@JSProperty
	String getName();

	@JSProperty
	String getDesc();

	@JSProperty
	int getAccessFlags();

	default boolean isPrivate() {
		return Modifier.isPrivate(getAccessFlags());
	}

	default boolean isVirtual() {
		return isMethod() && !Modifier.isStatic(getAccessFlags()) && !isPrivate();
	}

	default boolean isAbstract() {
		return Modifier.isAbstract(getAccessFlags());
	}
}

public interface ClassMember extends SimpleClassMember {
	@JSBody(
		params = {"owner", "method", "name", "desc", "accessFlags"}, 
		script = "return {owner: owner, method: method, name: name, desc: desc, accessFlags: accessFlags, newName: null, args: new Map(), vars: new Map()};"
	)
	static ClassMember create(ClassInstance owner, boolean method, String name, String desc, int accessFlags) {
		throw new UnsupportedOperationException();
	}

	@JSProperty
	ClassInstance getOwner();

	@JSProperty
	String getNewName();

	@JSProperty()
	void setNewName(String name);

	@JSProperty
	JSMap<JSNumber, JSString> getArgs();

	@JSProperty
	JSMap<JSNumber, JSString> getVars();
}