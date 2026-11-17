package com.chaosforge.medins.ui;

import com.chaosforge.medins.model.ImpactRecord;

import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.table.DefaultTableModel;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

/**
 * 中央影响面表（Swing 组件）。
 *
 * <p>列取自 {@link UiModel#tableColumns()}；必填排前的排序逻辑抽成纯静态函数，
 * headless 下可脱离组件直接断言。两档宽度均以列内截断 + tooltip 处理，不引入横向滚动条。
 */
public final class ImpactTablePanel extends JPanel {

    private final UiModel model;

    public ImpactTablePanel(UiModel model) {
        this.model = model;
        buildComponents();
    }

    private void buildComponents() {
        Object[] columns = model.tableColumns().toArray();
        DefaultTableModel tableModel = new DefaultTableModel(columns, 0);
        for (List<String> row : model.tableRows()) {
            Object[] cells = new Object[columns.length];
            for (int i = 0; i < columns.length; i++) {
                cells[i] = i < row.size() ? row.get(i) : "";
            }
            tableModel.addRow(cells);
        }
        JTable table = new JTable(tableModel);
        table.setRowHeight(Theme.ROW_HEIGHT);
        table.setFillsViewportHeight(true);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_ALL_COLUMNS);
        table.getTableHeader().setReorderingAllowed(false);
        table.setFont(Theme.tableFont());
        // 高密度表格的列宽偏好：口径注释与字段名较长，优先保证这两列可读，避免横向滚动条。
        int[] widths = {96, 138, 138, 92, 64, 84, 48, 116};
        for (int i = 0; i < Math.min(widths.length, table.getColumnCount()); i++) {
            table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        }
        setLayout(new java.awt.BorderLayout());
        add(new JScrollPane(table), java.awt.BorderLayout.CENTER);
    }

    /**
     * 必填字段排前（消费清单字典的必填位，由调用方以判定函数注入），
     * 同类按分区、字段名稳定排序，保证结果可复现。
     */
    public static List<ImpactRecord> requiredFirst(List<ImpactRecord> records,
                                                   Predicate<ImpactRecord> isRequired) {
        Predicate<ImpactRecord> predicate = isRequired == null ? r -> false : isRequired;
        List<ImpactRecord> sorted = new ArrayList<>(records == null ? List.<ImpactRecord>of() : records);
        Comparator<ImpactRecord> requiredFirst = Comparator
                .comparingInt((ImpactRecord r) -> predicate.test(r) ? 0 : 1)
                .thenComparing(ImpactRecord::section)
                .thenComparing(ImpactRecord::listFieldName);
        sorted.sort(requiredFirst);
        return sorted;
    }
}
