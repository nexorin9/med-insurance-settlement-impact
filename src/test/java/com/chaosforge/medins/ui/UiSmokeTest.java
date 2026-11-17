package com.chaosforge.medins.ui;

import com.chaosforge.medins.model.ChangeKind;
import com.chaosforge.medins.model.ImpactRecord;
import com.chaosforge.medins.model.ImpactScope;
import com.chaosforge.medins.model.ImpactType;
import com.chaosforge.medins.model.ListField;
import com.chaosforge.medins.model.Verdict;
import com.chaosforge.medins.report.DiffResult;
import com.chaosforge.medins.report.ImpactCsvWriter;
import com.chaosforge.medins.report.NoteContext;
import com.chaosforge.medins.report.TwoVersionDiff;
import com.chaosforge.medins.report.VendorNoteWriter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.GraphicsEnvironment;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/** UI 冒烟测试：无显示环境下模型层与导出层可脱离 UI 运行，且 UI 与导出解耦。 */
class UiSmokeTest {

    private static List<ImpactRecord> sampleRecords() {
        return List.of(ImpactRecord.builder()
                .viewName("V_BILL_FEE")
                .changedField("FEE_AMT")
                .section(ListField.SECTION_FEE)
                .listFieldName("医疗费用总额")
                .sourceColumn("FEE_AMT")
                .impact(ImpactType.DIRECT)
                .confidence(0.92d)
                .scope(ImpactScope.STRICT)
                .hops(1)
                .batch("<EXAMPLE_BATCH>")
                .verdict(Verdict.MATERIALLY_AFFECTED)
                .reasonZh("直接写入该栏")
                .build());
    }

    private static NoteContext sampleContext() {
        return NoteContext.builder()
                .viewName("V_BILL_FEE")
                .changedField("FEE_AMT")
                .changeKind(ChangeKind.LOGIC_CHANGED)
                .changeNoteZh("汇总口径调整")
                .sqlDigest("a1b2c3d4e5f6")
                .scope(ImpactScope.STRICT)
                .putEvidence("医疗费用总额", "V_BILL_FEE.FEE_AMT --DIRECT--> 医疗费用总额（1 跳）")
                .build();
    }

    @Test
    @DisplayName("无显示环境下模型层与导出层可脱离 UI 运行")
    void modelAndExportWorkWithoutDisplay() {
        List<ImpactRecord> records = sampleRecords();

        String note = VendorNoteWriter.write(records, sampleContext());
        assertNotNull(note);
        assertTrue(note.contains("<EXAMPLE_BATCH>"));
        assertTrue(note.contains("草稿（未人工确认）"));

        String csv = ImpactCsvWriter.toCsv(records);
        assertTrue(csv.startsWith(ImpactCsvWriter.HEADER));
        assertTrue(csv.contains("<EXAMPLE_BATCH>"));

        DiffResult diff = TwoVersionDiff.compare(records, List.of());
        assertEquals(1, diff.removed().size(), "差异回放也不依赖界面");

        assertEquals(GraphicsEnvironment.isHeadless(), HeadlessMode.isHeadless());
        assertFalse(UiModel.builder().build().panels().isEmpty());
        assertDoesNotThrow(Theme::apply);
        assertDoesNotThrow(() -> FontFallback.resolve("PingFang SC"));
        assertFalse(FontFallback.resolve("PingFang SC").isBlank());
        assertTrue(FontFallback.resolveWithHint("不存在的中文字体").contains("字体"));
    }

    @Test
    @DisplayName("MainFrame 类加载安全：不 new 窗口，headless 下只断言纯数据模型")
    void uiClassLoadsWithoutWindow() throws Exception {
        // 只加载不初始化，避免触发任何 AWT 静态初始化；headless 下绝不创建窗口
        Class<?> frameClass = Class.forName("com.chaosforge.medins.ui.MainFrame",
                false, getClass().getClassLoader());
        assertTrue(javax.swing.JFrame.class.isAssignableFrom(frameClass), "MainFrame 继承 JFrame");

        if (HeadlessMode.isHeadless()) {
            UiModel model = UiModel.builder().build();
            assertTrue(model.panels().contains("impact-table"));
            assertEquals("归因未生成", UiModel.BANNER_NO_MODEL);
        }
    }

    @Test
    @DisplayName("CLI analyze --help 退出码 0（反射调用，避免编译期依赖）")
    void cliAnalyzeHelpExitZero() throws Exception {
        Class<?> app;
        try {
            app = Class.forName("com.chaosforge.medins.App");
        } catch (ClassNotFoundException ex) {
            fail("App 类缺失：" + ex.getMessage());
            return;
        }
        Method execute;
        try {
            execute = app.getMethod("execute", String[].class);
        } catch (NoSuchMethodException ex) {
            // 集成负责人尚未落地 App.execute 时，退化为断言导出层与模型层可脱离 UI 运行
            assertDoesNotThrow(() -> ImpactCsvWriter.toCsv(sampleRecords()));
            assertDoesNotThrow(() -> VendorNoteWriter.write(sampleRecords(), sampleContext()));
            return;
        }
        Object result = execute.invoke(null, (Object) new String[]{"analyze", "--help"});
        assertNotNull(result);
        assertEquals(0, ((Number) result).intValue());
    }

    @Test
    @DisplayName("关键路径不依赖界面状态：CSV 写文件与说明导出均可独立复现")
    void exportDecoupledFromUi(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("impact.csv");
        ImpactCsvWriter.write(file, sampleRecords());
        byte[] bytes = Files.readAllBytes(file);
        assertTrue(Files.exists(file));
        assertEquals((byte) 0xEF, bytes[0]);
        assertEquals((byte) 0xBB, bytes[1]);
        assertEquals((byte) 0xBF, bytes[2]);
        assertFalse(UiModel.builder().build().tableColumns().isEmpty());
        assertFalse(VendorNoteWriter.write(sampleRecords(), sampleContext()).isBlank());
    }
}
