package mcsrc.remap;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;

import org.teavm.jso.JSExport;
import org.teavm.jso.JSObject;
import org.teavm.jso.core.JSArray;
import org.teavm.jso.core.JSMap;
import org.teavm.jso.core.JSNumber;
import org.teavm.jso.core.JSString;
import org.teavm.jso.typedarrays.ArrayBuffer;
import org.teavm.jso.typedarrays.Int8Array;

import net.fabricmc.mappingio.format.tiny.Tiny2FileReader;
import net.fabricmc.mappingio.tree.MemoryMappingTree;
import net.fabricmc.mappingio.tree.MappingTree.ClassMapping;
import net.fabricmc.mappingio.tree.MappingTree.FieldMapping;
import net.fabricmc.mappingio.tree.MappingTree.MethodArgMapping;
import net.fabricmc.mappingio.tree.MappingTree.MethodMapping;
import net.fabricmc.mappingio.tree.MappingTree.MethodVarMapping;

import mcsrc.Indexer;

public class MarshallingWorker extends Indexer {
	public static String getMemberID(boolean method, String name, String desc) {
		return method ? getMethodID(name, desc) : getFieldID(name, desc);
	}

	public static String getMethodID(String name, String desc) {
		return name.concat(desc);
	}

	public static String getFieldID(String name, String desc) {
		return name + ";;" + desc;
	}

	@JSExport
	public static JSMap<JSString, ClassInstance> loadMappings2(JSArray<IndexedClassInstance> indexedClasses, ArrayBuffer mappings) {
		byte[] mappingsArray = new Int8Array(mappings).copyToJavaArray();
		Reader mappingsReader = new InputStreamReader(new ByteArrayInputStream(mappingsArray), StandardCharsets.UTF_8);

		var tree = new MemoryMappingTree();
		try {
			Tiny2FileReader.read(mappingsReader, tree);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
		tree.setIndexByDstNames(true);

		JSMap<JSString, ClassInstance> classes = new JSMap<>();
		Utils.forEach(indexedClasses, (cls, index, array) -> {
			ClassInstance out = ClassInstance.create(cls.getClassName(), cls.getSuperName(), cls.getInterfaces(), cls.getAccessFlags());

			Utils.forEach(cls.getMembers(), (member, memberIndex, members) -> {
				JSString id = JSString.valueOf(getMemberID(member.isMethod(), member.getName(), member.getDesc()));
				out.getMembers().set(id, ClassMember.create(out, member.isMethod(), member.getName(), member.getDesc(), member.getAccessFlags()));
			});

			classes.set(JSString.valueOf(cls.getClassName()), out);
		});
		merge(classes);
		propagate(classes, tree);
		return classes;
	}

	private static void merge(JSMap<JSString, ClassInstance> classes) {
		Utils.forEach(classes, (node, name, map) -> {
			ClassInstance parent = map.get(JSString.valueOf(node.getSuperName()));

			if (parent != null) {
				node.getParents().add(parent);
				parent.getChildren().add(node);
			}

			for (int i = 0; i < node.getInterfaces().getLength(); i++) {
				parent = map.get(node.getInterfaces().get(i));

				if (parent != null) {
					node.getParents().add(parent);
					parent.getChildren().add(node);
				}
			}
		});
	}

	private static void propagate(JSMap<JSString, ClassInstance> classes, MemoryMappingTree tree) {
		int official = tree.getNamespaceId("official");
		int named = tree.getNamespaceId("named");
		JSSet<ClassInstance> visitedUp = new JSSet<>();
		JSSet<ClassInstance> visitedDown = new JSSet<>();

		for (ClassMapping clazz : tree.getClasses()) {
			String owner = clazz.getName(official);

			ClassInstance cls = classes.get(JSString.valueOf(owner));
			if (cls == null) continue; //Missing
			cls.setNewName(clazz.getName(named));

			for (FieldMapping field : clazz.getFields()) {
				propagate(visitedUp, visitedDown, cls, false, field.getName(official), field.getDesc(official), field.getName(named));
			}

			for (MethodMapping method : clazz.getMethods()) {
				ClassMember member = propagate(visitedUp, visitedDown, cls, true, method.getName(official), method.getDesc(official), method.getName(named));

				if (member != null) {//May be missing
					for (MethodArgMapping arg : method.getArgs()) {
						member.getArgs().set(JSNumber.valueOf(arg.getLvIndex()), JSString.valueOf(arg.getName(named)));
					}

					for (MethodVarMapping var : method.getVars()) {
						member.getVars().set(JSNumber.valueOf(var.getLvIndex()), JSString.valueOf(var.getName(named)));
					}
				}
			}
		}
	}

	private enum Direction {
		UP,
		ANY,
		DOWN,
	}

	private static ClassMember propagate(JSSet<ClassInstance> visitedUp, JSSet<ClassInstance> visitedDown,
			ClassInstance cls, boolean method, String name, String desc, String newName) {
		String memberID = getMemberID(method, name, desc);
		ClassMember member = cls.getMembers().get(JSString.valueOf(memberID));

		if (member != null && !name.equals(newName)) {
			visitedUp.add(cls);
			visitedDown.add(cls);
			boolean isVirtual = member.isVirtual();
			propagate(visitedUp, visitedDown, cls, method, memberID, newName,
					(isVirtual ? Direction.ANY : Direction.DOWN), isVirtual, true);
			visitedUp.clear();
			visitedDown.clear();
		}

		return member;
	}

	private static <T extends JSObject> boolean didAdd(JSSet<T> set, T obj) {
		int size = set.getSize();
		set.add(obj);
		return size < set.getSize();
	}

	private static void propagate(JSSet<ClassInstance> visitedUp, JSSet<ClassInstance> visitedDown,
			ClassInstance cls, boolean method, String memberID, String newName,
			Direction dir, boolean isVirtual, boolean first) {
		ClassMember member = cls.getMembers().get(JSString.valueOf(memberID));

		if (member != null) {
			if (!first && !isVirtual) {
				return;
			}

			if (first || member.isVirtual()) {
				member.setNewName(newName);
			}

			if (first && (member.isPrivate() || method && cls.isInterface() && !isVirtual)) {
				return;
			}
		}

		if (dir == Direction.ANY || dir == Direction.UP || isVirtual && member != null && member.isVirtual()) {
			cls.getParents().forEach(node -> {
				if (didAdd(visitedUp, node)) {
					propagate(visitedUp, visitedDown, node, method, memberID, newName, Direction.UP, isVirtual, false);
				}
			});
		}

		if (dir == Direction.ANY || dir == Direction.DOWN || isVirtual && member != null && member.isVirtual()) {
			cls.getChildren().forEach(node -> {
				if (didAdd(visitedDown, node)) {
					propagate(visitedUp, visitedDown, node, method, memberID, newName, Direction.DOWN, isVirtual, false);
				}
			});
		}
	}


	@JSExport
    public static IndexedClassInstance index2(ArrayBuffer arrayBuffer) {
		return RemapWorker.index2(arrayBuffer);
	}

	@JSExport
	public static void receiveClasses(JSMap<JSString, ClassInstance> classes) {
		RemapWorker.receiveClasses(classes);
	}

	@JSExport
	public static Int8Array remapEntry2(ArrayBuffer entry) {
		return RemapWorker.remapEntry2(entry);
	}
	}