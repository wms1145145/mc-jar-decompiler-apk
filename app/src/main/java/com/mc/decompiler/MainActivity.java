package com.mc.decompiler;

import android.Manifest;
import android.app.AlertDialog;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Environment;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public class MainActivity extends AppCompatActivity {
    private TextView tvStatus;
    private TextView tvLog;
    private ScrollView scrollView;
    private EditText etPath;
    private CheckBox cbFastMode;
    private static final int PERMISSION_CODE = 100;
    private String currentPath = Environment.getExternalStorageDirectory().getAbsolutePath();
    private boolean isProcessing = false;
    private boolean useFastMode = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(30, 30, 30, 30);

        TextView tvTitle = new TextView(this);
        tvTitle.setText("请输入或选择 JAR 路径:");
        tvTitle.setTextSize(16);
        layout.addView(tvTitle);

        etPath = new EditText(this);
        etPath.setHint("/storage/emulated/0/xxx.jar");
        etPath.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        layout.addView(etPath);

        LinearLayout btnLayout = new LinearLayout(this);
        btnLayout.setOrientation(LinearLayout.HORIZONTAL);
        btnLayout.setPadding(0, 20, 0, 20);

        Button btnBrowse = new Button(this);
        btnBrowse.setText("浏览文件");
        btnBrowse.setOnClickListener(v -> showFileChooser(currentPath));
        btnLayout.addView(btnBrowse);

        Button btnStart = new Button(this);
        btnStart.setText("开始反编译");
        btnStart.setOnClickListener(v -> {
            if (isProcessing) {
                Toast.makeText(this, "正在处理中，请稍候...", Toast.LENGTH_SHORT).show();
                return;
            }
            String path = etPath.getText().toString().trim();
            if (path.isEmpty()) {
                Toast.makeText(this, "请输入路径！", Toast.LENGTH_SHORT).show();
                return;
            }
            File jarFile = new File(path);
            if (!jarFile.exists()) {
                tvStatus.setText("❌ 找不到文件：" + path);
                return;
            }
            tvLog.setText("");
            tvStatus.setText("准备处理...");
            isProcessing = true;
            new Thread(() -> processJar(jarFile)).start();
        });
        btnLayout.addView(btnStart);
        layout.addView(btnLayout);

        cbFastMode = new CheckBox(this);
        cbFastMode.setText("极速模式 (免解压直连反编译)");
        cbFastMode.setChecked(true);
        cbFastMode.setOnCheckedChangeListener((btn, isChecked) -> useFastMode = isChecked);
        layout.addView(cbFastMode);

        tvStatus = new TextView(this);
        tvStatus.setText("准备就绪");
        tvStatus.setTextSize(16);
        tvStatus.setPadding(0, 10, 0, 10);
        layout.addView(tvStatus);

        scrollView = new ScrollView(this);
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.0f);
        scrollView.setLayoutParams(scrollParams);
        
        tvLog = new TextView(this);
        tvLog.setTextSize(12);
        tvLog.setTextColor(0xFF00FF00);
        tvLog.setBackgroundColor(0xFF1E1E1E);
        tvLog.setPadding(20, 20, 20, 20);
        tvLog.setText("系统就绪...\n");
        scrollView.addView(tvLog);
        layout.addView(scrollView);

        setContentView(layout);

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE}, PERMISSION_CODE);
        }
    }

    private void updateStatus(String msg) {
        runOnUiThread(() -> tvStatus.setText(msg));
        appendLog("[STATUS] " + msg);
    }

    private void appendLog(String msg) {
        runOnUiThread(() -> {
            tvLog.append(msg + "\n");
            scrollView.post(() -> scrollView.fullScroll(View.FOCUS_DOWN));
        });
    }

    private void showFileChooser(String path) {
        File dir = new File(path);
        if (!dir.exists() || !dir.isDirectory()) return;

        File[] files = dir.listFiles();
        if (files == null) return;

        Arrays.sort(files, (f1, f2) -> {
            if (f1.isDirectory() && !f2.isDirectory()) return -1;
            if (!f1.isDirectory() && f2.isDirectory()) return 1;
            return f1.getName().compareToIgnoreCase(f2.getName());
        });

        List<String> names = new ArrayList<>();
        List<File> validFiles = new ArrayList<>();

        if (!path.equals(Environment.getExternalStorageDirectory().getAbsolutePath())) {
            names.add("📂 .. (返回上一级)");
            validFiles.add(dir.getParentFile());
        }

        for (File f : files) {
            if (f.isDirectory() && !f.getName().startsWith(".")) {
                names.add("📁 " + f.getName());
                validFiles.add(f);
            } else if (f.getName().toLowerCase().endsWith(".jar")) {
                names.add("📄 " + f.getName());
                validFiles.add(f);
            }
        }

        new AlertDialog.Builder(this)
                .setTitle("浏览: " + path)
                .setItems(names.toArray(new String[0]), (dialog, which) -> {
                    File selected = validFiles.get(which);
                    if (selected.isDirectory()) {
                        currentPath = selected.getAbsolutePath();
                        showFileChooser(currentPath);
                    } else {
                        etPath.setText(selected.getAbsolutePath());
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void processJar(File originalJar) {
        String baseName = originalJar.getName().replace(".jar", "");
        File workDir = new File(getFilesDir(), "workspace");
        File unzipDir = new File(workDir, baseName);
        File rebuiltZip = new File(workDir, baseName + "_rebuilt.zip");
        
        File externalDir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (externalDir == null) externalDir = getFilesDir();
        File finalOutDir = new File(externalDir, baseName + "_src");

        File targetFile = originalJar;

        try {
            deleteDir(workDir);
            workDir.mkdirs();
            if (finalOutDir.exists()) deleteDir(finalOutDir);
            finalOutDir.mkdirs();

            File binFile = new File(getFilesDir(), "javajive");
            if (!binFile.exists()) {
                updateStatus("释放反编译引擎 (极速拷贝)...");
                try (InputStream is = getAssets().open("javajive_arm64");
                     OutputStream os = new FileOutputStream(binFile)) {
                    byte[] buffer = new byte[65536];
                    int read;
                    while ((read = is.read(buffer)) != -1) os.write(buffer, 0, read);
                }
            }
            binFile.setExecutable(true, false);
            appendLog("引擎就绪。");

            if (!useFastMode) {
                updateStatus("正在解压原包...");
                unzip(originalJar, unzipDir);
                updateStatus("正在重新压缩为 ZIP...");
                zipDir(unzipDir, rebuiltZip);
                targetFile = rebuiltZip;
            }

            updateStatus("开始反编译源码...");
            ProcessBuilder pb = new ProcessBuilder(
                    binFile.getAbsolutePath(), "decompile",
                    "-o", finalOutDir.getAbsolutePath(), targetFile.getAbsolutePath()
            );
            pb.redirectErrorStream(true);
            Process process = pb.start();

            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line;
            StringBuilder errorMsg = new StringBuilder();
            
            Pattern decompiledPattern = Pattern.compile("Decompiled \\[([^\\]]+\\.class)\\]");

            while ((line = reader.readLine()) != null) {
                errorMsg.append(line).append("\n");
                Matcher m = decompiledPattern.matcher(line);
                if (m.find()) {
                    String className = m.group(1).replace(".class", ".java");
                    appendLog("✅ 生成: " + className);
                }
            }
            int exitCode = process.waitFor();

            if (exitCode != 0) {
                String finalErr = errorMsg.toString();
                if (finalErr.length() > 500) finalErr = finalErr.substring(finalErr.length() - 500);
                updateStatus("❌ 反编译失败");
                appendLog("错误代码: " + exitCode + "\n" + finalErr);
                if (useFastMode) {
                    appendLog("💡 提示：请取消勾选“极速模式”重新洗包后再试！");
                }
                return;
            }

            updateStatus("✅ 成功！路径: Android/data/" + getPackageName() + "/files/Download/" + baseName + "_src");
            isProcessing = false;

        } catch (Exception e) {
            e.printStackTrace();
            updateStatus("❌ 异常: " + e.getMessage());
            appendLog("异常堆栈:\n" + Arrays.toString(e.getStackTrace()));
            isProcessing = false;
        } finally {
            deleteDir(workDir);
            appendLog("清理完毕。");
        }
    }

    private void unzip(File zipFile, File targetDir) throws IOException {
        if (!targetDir.exists()) targetDir.mkdirs();
        try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(new FileInputStream(zipFile)))) {
            ZipEntry entry;
            byte[] buffer = new byte[65536];
            while ((entry = zis.getNextEntry()) != null) {
                File file = new File(targetDir, entry.getName());
                if (entry.isDirectory()) {
                    file.mkdirs();
                } else {
                    File parent = file.getParentFile();
                    if (parent != null && !parent.exists()) parent.mkdirs();
                    try (FileOutputStream fos = new FileOutputStream(file)) {
                        int len;
                        while ((len = zis.read(buffer)) > 0) {
                            fos.write(buffer, 0, len);
                        }
                    }
                }
                zis.closeEntry();
            }
        }
    }

    private void zipDir(File sourceDir, File zipFile) throws IOException {
        try (ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(zipFile)))) {
            zipSubDir(sourceDir, sourceDir, zos);
        }
    }

    private void zipSubDir(File baseDir, File currentDir, ZipOutputStream zos) throws IOException {
        File[] files = currentDir.listFiles();
        if (files == null) return;
        byte[] buffer = new byte[65536];
        for (File file : files) {
            String relativePath = baseDir.toURI().relativize(file.toURI()).getPath();
            if (file.isDirectory()) {
                zos.putNextEntry(new ZipEntry(relativePath));
                zos.closeEntry();
                zipSubDir(baseDir, file, zos);
            } else {
                zos.putNextEntry(new ZipEntry(relativePath));
                try (FileInputStream fis = new FileInputStream(file)) {
                    int len;
                    while ((len = fis.read(buffer)) > 0) {
                        zos.write(buffer, 0, len);
                    }
                }
                zos.closeEntry();
            }
        }
    }

    private void deleteDir(File dir) {
        if (dir != null && dir.isDirectory()) {
            String[] children = dir.list();
            if (children != null) for (String child : children) deleteDir(new File(dir, child));
        }
        if (dir != null) dir.delete();
    }
}
