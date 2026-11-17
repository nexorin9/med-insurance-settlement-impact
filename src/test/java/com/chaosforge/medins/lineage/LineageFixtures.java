package com.chaosforge.medins.lineage;

import com.chaosforge.medins.model.EdgeKind;
import com.chaosforge.medins.model.FieldKey;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * lineage 测试共用的小工具：字段键、构图、集合比较。
 *
 * <p>所有字段串统一写成单点的 {@code VIEW.COLUMN}（列名内不再含点），拆分时按第一个点切分，
 * 与 {@link FieldKey#of} 的大写规范化一致。仅测试内使用，不依赖任何其它代理包。
 */
public final class LineageFixtures {

    private LineageFixtures() { }

    /** 由视图名与列名构造字段键。 */
    public static FieldKey fk(String view, String column) {
        return FieldKey.of(view, column);
    }

    /** 由单点规范串 VIEW.COLUMN 构造字段键。 */
    public static FieldKey fk(String dotted) {
        int dot = dotted.indexOf('.');
        if (dot <= 0 || dot == dotted.length() - 1) {
            throw new IllegalArgumentException("字段串需形如 VIEW.COLUMN：" + dotted);
        }
        return FieldKey.of(dotted.substring(0, dot), dotted.substring(dot + 1));
    }

    /** 便捷加边：from / to 均为 VIEW.COLUMN 规范串。 */
    public static void edge(FieldLineageGraph g, String from, String to, EdgeKind kind, String view) {
        g.addEdge(fk(from), fk(to), kind, view);
    }

    /** 便捷加边：视图名取 to 的视图部分。 */
    public static void edge(FieldLineageGraph g, String from, String to, EdgeKind kind) {
        g.addEdge(fk(from), fk(to), kind, fk(to).view());
    }

    /** 把字段键集合转成规范串集合（保序）。 */
    public static Set<String> keys(Set<FieldKey> fieldKeys) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (FieldKey k : fieldKeys) {
            out.add(k.key());
        }
        return out;
    }

    /** 把可达字段集合按迭代顺序转成规范串列表，用于断言同序稳定。 */
    public static List<String> ordered(Set<FieldKey> fieldKeys) {
        return List.copyOf(keys(fieldKeys));
    }

    /** 由若干规范串造有序集合。 */
    public static Set<String> set(String... items) {
        return new LinkedHashSet<>(Arrays.asList(items));
    }
}
