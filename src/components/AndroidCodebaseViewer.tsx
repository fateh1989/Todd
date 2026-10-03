import React, { useState } from 'react';
import { FileCode, Folder, Check, Terminal, ExternalLink } from 'lucide-react';

interface FileNode {
  path: string;
  name: string;
  type: string;
}

export const AndroidCodebaseViewer: React.FC = () => {
  const files: FileNode[] = [
    { path: '/app/build.gradle.kts', name: 'app/build.gradle.kts', type: 'Gradle Config' },
    { path: '/build.gradle.kts', name: 'build.gradle.kts', type: 'Root Gradle' },
    { path: '/settings.gradle.kts', name: 'settings.gradle.kts', type: 'Gradle Settings' },
    { path: '/gradle/libs.versions.toml', name: 'libs.versions.toml', type: 'Version Catalog' },
    { path: '/app/src/main/AndroidManifest.xml', name: 'AndroidManifest.xml', type: 'Manifest' },
    { path: '/app/src/main/kotlin/com/todd/ToddApplication.kt', name: 'ToddApplication.kt', type: 'Application' },
    { path: '/app/src/main/kotlin/com/todd/core/state/ToddStateMachine.kt', name: 'ToddStateMachine.kt', type: 'Core State' },
    { path: '/app/src/main/kotlin/com/todd/core/model/Models.kt', name: 'Models.kt', type: 'Domain Models' },
    { path: '/app/src/main/kotlin/com/todd/core/ai/AIRouter.kt', name: 'AIRouter.kt', type: 'AI Router' },
    { path: '/app/src/main/kotlin/com/todd/core/ai/GeminiLiveClient.kt', name: 'GeminiLiveClient.kt', type: 'Live Voice Client' },
    { path: '/app/src/main/kotlin/com/todd/core/ai/MockAIProvider.kt', name: 'MockAIProvider.kt', type: 'AI Provider' },
    { path: '/app/src/main/kotlin/com/todd/core/ai/GeminiAIProvider.kt', name: 'GeminiAIProvider.kt', type: 'Cloud AI' },
    { path: '/app/src/main/kotlin/com/todd/data/local/ToddDatabase.kt', name: 'ToddDatabase.kt', type: 'Room DB' },
    { path: '/app/src/main/kotlin/com/todd/data/local/Daos.kt', name: 'Daos.kt', type: 'Room DAOs' },
    { path: '/app/src/main/kotlin/com/todd/data/repository/ToddRepository.kt', name: 'ToddRepository.kt', type: 'Repository' },
    { path: '/app/src/main/kotlin/com/todd/service/overlay/FloatingToddService.kt', name: 'FloatingToddService.kt', type: 'Overlay Service' },
    { path: '/app/src/main/kotlin/com/todd/service/ime/ToddInputMethodService.kt', name: 'ToddInputMethodService.kt', type: 'IME Keyboard' },
    { path: '/app/src/main/kotlin/com/todd/service/accessibility/ToddAccessibilityService.kt', name: 'ToddAccessibilityService.kt', type: 'Accessibility' },
    { path: '/app/src/main/kotlin/com/todd/ui/MainActivity.kt', name: 'MainActivity.kt', type: 'Compose UI' },
    { path: '/app/src/test/kotlin/com/todd/GeminiLiveVoiceTest.kt', name: 'GeminiLiveVoiceTest.kt', type: 'Voice Unit Test' },
    { path: '/app/src/test/kotlin/com/todd/ToddStateMachineTest.kt', name: 'ToddStateMachineTest.kt', type: 'Unit Test' },
    { path: '/.github/workflows/android.yml', name: '.github/workflows/android.yml', type: 'CI Workflow' },
  ];

  const [selectedFile, setSelectedFile] = useState<FileNode>(files[0]);

  return (
    <div className="bg-slate-900 border border-slate-800 rounded-2xl p-4 shadow-xl">
      <div className="flex items-center justify-between mb-4 pb-2 border-b border-slate-800">
        <div>
          <h4 className="font-bold text-sm text-white">
            شجرة ملفات معمارية أندرويد الأصلية (Kotlin + Compose)
          </h4>
          <p className="text-xs text-slate-400 mt-0.5">
            المشروع مبني بالكامل وجاهز للبناء عبر Gradle وحزمة GitHub Actions في المستودع نفسه.
          </p>
        </div>
        <div className="flex items-center gap-2">
          <span className="text-[11px] bg-indigo-950 text-indigo-300 border border-indigo-800 px-2 py-0.5 rounded-full font-mono">
            Gradle Kotlin DSL (compileSdk 35)
          </span>
        </div>
      </div>

      <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
        {/* Files Navigation */}
        <div className="space-y-1 max-h-[360px] overflow-y-auto pr-1">
          {files.map((file) => (
            <button
              key={file.path}
              onClick={() => setSelectedFile(file)}
              className={`w-full text-right px-3 py-2 rounded-xl text-xs flex items-center justify-between transition ${
                selectedFile.path === file.path
                  ? 'bg-indigo-600/30 text-indigo-200 border border-indigo-500/50'
                  : 'bg-slate-950/60 hover:bg-slate-800 text-slate-400 border border-slate-800/40'
              }`}
            >
              <div className="flex items-center gap-2 overflow-hidden">
                <FileCode className="w-3.5 h-3.5 text-indigo-400 shrink-0" />
                <span className="truncate font-mono text-[11px]">{file.name}</span>
              </div>
              <span className="text-[10px] text-slate-500 font-sans">{file.type}</span>
            </button>
          ))}
        </div>

        {/* File Detail Card */}
        <div className="md:col-span-2 bg-slate-950 border border-slate-800 rounded-xl p-4 flex flex-col justify-between">
          <div>
            <div className="flex items-center justify-between pb-2 border-b border-slate-800 mb-3">
              <span className="font-mono text-xs text-indigo-300">{selectedFile.path}</span>
              <span className="text-[10px] bg-slate-800 text-slate-300 px-2 py-0.5 rounded-md">
                {selectedFile.type}
              </span>
            </div>
            <p className="text-xs text-slate-300 leading-relaxed mb-3">
              تم إنشاء هذا الملف الأصلي وفق مواصفات <strong>TODD_MASTER_SPEC.md</strong> ومطابق تماماً
              للمعايير الهندسية الخاصة بنظام أندرويد وJetpack Compose مع دعم كامل لقاعدة بيانات Room،
              خدمة الزر العائم ذات الأهداف المزدوجة، خدمة لوحة المفاتيح IME، وخدمة الوعي الشاشي.
            </p>
            <div className="space-y-1.5 text-xs text-slate-400">
              <div className="flex items-center gap-2">
                <Check className="w-3.5 h-3.5 text-emerald-400" />
                <span>حزم برمجية موحدة تحت نطاق <code>com.todd</code></span>
              </div>
              <div className="flex items-center gap-2">
                <Check className="w-3.5 h-3.5 text-emerald-400" />
                <span>توافق تام مع معايير الأمان وعدم حفظ أي أسرار في شفرة المصدر</span>
              </div>
              <div className="flex items-center gap-2">
                <Check className="w-3.5 h-3.5 text-emerald-400" />
                <span>سير عمل GitHub Actions محدد في <code>.github/workflows/android.yml</code></span>
              </div>
            </div>
          </div>

          <div className="pt-4 border-t border-slate-900 mt-4 flex items-center justify-between text-xs text-slate-500 font-mono">
            <span>Branch: main</span>
            <span>Target: fateh1989/Todd</span>
          </div>
        </div>
      </div>
    </div>
  );
};
