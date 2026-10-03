import React, { useState, useEffect } from 'react';
import {
  ToddGlobalStatus,
  AIProviderMode,
  Task,
  Project,
  MemoryEntry,
  FailureRecord,
} from './types';
import { AIRouter } from './services/aiService';
import { FloatingButtonSimulator } from './components/FloatingButtonSimulator';
import { KeyboardSimulator } from './components/KeyboardSimulator';
import { VoiceSimulator } from './components/VoiceSimulator';
import { TaskStateMachineView } from './components/TaskStateMachineView';
import { AndroidCodebaseViewer } from './components/AndroidCodebaseViewer';
import { RemoteCodingSimulator } from './components/RemoteCodingSimulator';
import { RulesEngineViewer } from './components/RulesEngineViewer';
import { GeminiLiveVoiceModal } from './components/GeminiLiveVoiceModal';
import {
  Layers,
  Keyboard,
  Mic,
  CheckCircle2,
  FolderGit2,
  Power,
  Play,
  Pause,
  Sparkles,
  Cpu,
  Shield,
  FileCode,
  Terminal,
  Radio,
} from 'lucide-react';

const aiRouter = new AIRouter();

export const App: React.FC = () => {
  // 1. Persistent Todd Global State
  const [globalStatus, setGlobalStatus] = useState<ToddGlobalStatus>('IDLE');
  const [isPaused, setIsPaused] = useState(false);
  const [isPowerOff, setIsPowerOff] = useState(false);
  const [isOverlayVisible, setIsOverlayVisible] = useState(true);
  const [aiMode, setAiMode] = useState<AIProviderMode>('AUTO');
  const [isLiveVoiceOpen, setIsLiveVoiceOpen] = useState(false);
  const [activeTab, setActiveTab] = useState<
    'overview' | 'overlay' | 'keyboard' | 'voice' | 'tasks' | 'remote' | 'rules' | 'codebase'
  >('overview');

  // 2. Persistent Projects
  const [projects, setProjects] = useState<Project[]>([
    {
      id: 'todd-core',
      name: 'Todd — Personal AI Agent',
      description: 'المساعد الشخصي الهجين لنظام أندرويد',
      repository: 'fateh1989/Todd',
      branch: 'main',
      lastVerifiedCommit: '2afed3d1600d42577d6ce6aa9fab151729d6b11d',
      currentGoal: 'بناء المعمارية الأصلية لأندرويد والتحقق من سير عمل المهام',
      status: 'ACTIVE',
      createdAt: Date.now() - 86400000,
      updatedAt: Date.now(),
    },
  ]);

  // 3. Persistent Tasks
  const [tasks, setTasks] = useState<Task[]>([
    {
      id: 'task-101',
      projectId: 'todd-core',
      title: 'بناء مشروع أندرويد الأصلي وتكوين Gradle وCompose',
      goal: 'تأسيس مشروع Android متكامل باستخدام Kotlin وJetpack Compose',
      status: 'VERIFIED',
      currentStep: 'تم إنشاء ملفات Gradle والـ Manifest وRoom DAOs بنجاح',
      completionCriteria: 'اكتمال ملفات البناء وسير عمل GitHub Actions',
      lastEvidence: 'تم التحقق من build.gradle.kts وAndroidManifest.xml وسير عمل CI في .github/workflows/android.yml',
      createdAt: Date.now() - 3600000,
      updatedAt: Date.now(),
    },
    {
      id: 'task-102',
      projectId: 'todd-core',
      title: 'تنفيذ الزر العائم ولوحة المفاتيح والتحقق من عدم كسر القواعد',
      goal: 'تطبيق خدمة FloatingToddService ولوحة ToddInputMethodService',
      status: 'VERIFIED',
      currentStep: 'تم اختبار السحب، أهداف الإخفاء، والإيقاف النهائي بالتعليق (Dwell)',
      completionCriteria: 'اجتياز فحص آليات التفاعل المزدوجة',
      lastEvidence: 'تم التحقق من تفاعل Dwell Power Off بنسبة 100% وحفظ الحالة',
      createdAt: Date.now() - 1800000,
      updatedAt: Date.now(),
    },
  ]);

  const [lastVerifiedAction, setLastVerifiedAction] = useState(
    'تم التحقق من بنية حزم أندرويد وملفات Kotlin الأصلية بنجاح'
  );
  const [nextPlannedAction, setNextPlannedAction] = useState(
    'تأكيد تشغيل اختبارات الحالة ودفع التغييرات إلى المستودع'
  );

  // Handlers for State Machine
  const handleTogglePause = () => {
    setIsPaused((prev) => {
      const next = !prev;
      setGlobalStatus(next ? 'PAUSED' : 'IDLE');
      return next;
    });
  };

  const handlePowerOff = () => {
    setIsPowerOff(true);
    setIsPaused(true);
    setIsOverlayVisible(false);
    setGlobalStatus('OFF');
    setNextPlannedAction('Todd متوقف تماماً. قم بتشغيله للعودة للعمل.');
  };

  const handlePowerOn = () => {
    setIsPowerOff(false);
    setIsPaused(false);
    setIsOverlayVisible(true);
    setGlobalStatus('IDLE');
    setNextPlannedAction('جاهز لتلقي أوامر المالك وتنفيذ المهام.');
  };

  const handlePlanTask = (title: string, goal: string, criteria: string) => {
    const newTask: Task = {
      id: `task-${Date.now()}`,
      projectId: 'todd-core',
      title,
      goal,
      status: 'PLANNED',
      currentStep: `تم التخطيط: ${goal}`,
      completionCriteria: criteria,
      createdAt: Date.now(),
      updatedAt: Date.now(),
    };
    setTasks([newTask, ...tasks]);
    setNextPlannedAction(`تنفيذ الخطوة الأولى للمهمة: ${title}`);
  };

  const handleExecuteStep = (taskId: string) => {
    setTasks(
      tasks.map((t) => {
        if (t.id !== taskId) return t;
        if (t.status === 'PLANNED') {
          return {
            ...t,
            status: 'IN_PROGRESS',
            currentStep: 'جاري التنفيذ البرمجي أو استدعاء الأداة...',
            updatedAt: Date.now(),
          };
        }
        if (t.status === 'IN_PROGRESS') {
          // EXECUTED is NOT VERIFIED
          return {
            ...t,
            status: 'EXECUTED',
            currentStep: 'تم التنفيذ الأولي بنجاح. في انتظار دليل التحقق الملموس.',
            updatedAt: Date.now(),
          };
        }
        return t;
      })
    );
  };

  const handleVerifyTask = (taskId: string, isSuccess: boolean, evidence: string) => {
    setTasks(
      tasks.map((t) => {
        if (t.id !== taskId) return t;
        if (isSuccess) {
          setLastVerifiedAction(`تم التحقق: ${t.title} (${evidence})`);
          return {
            ...t,
            status: 'VERIFIED',
            lastEvidence: evidence,
            currentStep: `تم التحقق بنجاح مع إثبات: ${evidence}`,
            updatedAt: Date.now(),
          };
        } else {
          return {
            ...t,
            status: 'FAILED',
            failureCause: evidence,
            currentStep: `فشل التحقق: ${evidence}`,
            updatedAt: Date.now(),
          };
        }
      })
    );
  };

  const handleCompleteTask = (taskId: string) => {
    setTasks(
      tasks.map((t) => {
        if (t.id === taskId && t.status === 'VERIFIED') {
          return {
            ...t,
            status: 'COMPLETED',
            currentStep: 'مكتملة ومثبتة رسمياً.',
            updatedAt: Date.now(),
          };
        }
        return t;
      })
    );
  };

  return (
    <div className="min-h-screen bg-slate-950 text-slate-100 flex flex-col font-sans" dir="rtl">
      {/* Top Header */}
      <header className="border-b border-slate-800/80 bg-slate-900/60 backdrop-blur-md sticky top-0 z-40">
        <div className="max-w-7xl mx-auto px-4 sm:px-6 h-16 flex items-center justify-between">
          <div className="flex items-center gap-3">
            <div className="w-10 h-10 rounded-xl bg-gradient-to-tr from-indigo-600 to-purple-600 flex items-center justify-center shadow-lg shadow-indigo-600/30">
              <Sparkles className="w-5 h-5 text-white" />
            </div>
            <div>
              <div className="flex items-center gap-2">
                <h1 className="font-bold text-base text-white tracking-tight">Todd</h1>
                <span className="text-[11px] bg-indigo-950 text-indigo-300 border border-indigo-800/60 px-2 py-0.5 rounded-full font-medium">
                  Android Native & Studio Controller
                </span>
              </div>
              <p className="text-xs text-slate-400">
                المساعد الهجين المستمر والخاص لمالك واحد (fateh1989/Todd)
              </p>
            </div>
          </div>

          {/* Quick System Controls */}
          <div className="flex items-center gap-3">
            {/* Gemini Live Voice Session Button */}
            <button
              onClick={() => setIsLiveVoiceOpen(true)}
              disabled={isPowerOff}
              className="flex items-center gap-1.5 px-3.5 py-1.5 rounded-xl text-xs font-bold bg-gradient-to-r from-cyan-600 to-indigo-600 hover:from-cyan-500 hover:to-indigo-500 text-white shadow-lg shadow-indigo-600/30 transition active:scale-95 disabled:opacity-40"
            >
              <Radio className="w-3.5 h-3.5 animate-pulse" />
              <span>محادثة صوتية (Gemini Live)</span>
            </button>

            {/* Global Pause / Resume */}
            <button
              onClick={handleTogglePause}
              disabled={isPowerOff}
              className={`flex items-center gap-1.5 px-3 py-1.5 rounded-xl text-xs font-semibold border transition disabled:opacity-40 ${
                isPaused
                  ? 'bg-amber-500/20 border-amber-500 text-amber-300 hover:bg-amber-500/30'
                  : 'bg-slate-800 hover:bg-slate-700 text-slate-300 border-slate-700'
              }`}
            >
              {isPaused ? <Play className="w-3.5 h-3.5" /> : <Pause className="w-3.5 h-3.5" />}
              <span>{isPaused ? 'استئناف Todd' : 'إيقاف مؤقت'}</span>
            </button>

            {/* Global Power Control */}
            <button
              onClick={isPowerOff ? handlePowerOn : handlePowerOff}
              className={`flex items-center gap-1.5 px-3.5 py-1.5 rounded-xl text-xs font-semibold border transition shadow-sm ${
                isPowerOff
                  ? 'bg-emerald-600 hover:bg-emerald-500 text-white border-emerald-500 shadow-emerald-600/30'
                  : 'bg-rose-600/20 hover:bg-rose-600/30 border-rose-600/50 text-rose-300'
              }`}
            >
              <Power className="w-3.5 h-3.5" />
              <span>{isPowerOff ? 'تشغيل Todd' : 'إيقاف تام (Power Off)'}</span>
            </button>
          </div>
        </div>
      </header>

      {/* Main Body */}
      <main className="flex-1 max-w-7xl mx-auto w-full px-4 sm:px-6 py-6 space-y-6">
        {/* Status Banner */}
        <div className="bg-slate-900 border border-slate-800 rounded-2xl p-4 shadow-lg">
          <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4">
            <div className="flex items-center gap-3">
              <span
                className={`w-3.5 h-3.5 rounded-full shrink-0 ${
                  isPowerOff
                    ? 'bg-rose-500'
                    : isPaused
                    ? 'bg-amber-400'
                    : globalStatus === 'WORKING'
                    ? 'bg-indigo-400 animate-ping'
                    : 'bg-emerald-400'
                }`}
              />
              <div>
                <div className="flex items-center gap-2">
                  <span className="font-bold text-sm text-white">حالة Todd:</span>
                  <span className="text-xs font-medium text-slate-300 font-mono">
                    {isPowerOff
                      ? 'مغلق تماماً (Power OFF)'
                      : isPaused
                      ? 'إيقاف مؤقت (PAUSED)'
                      : `نشط وجاهز للعمل (${globalStatus})`}
                  </span>
                </div>
                <div className="text-xs text-slate-400 mt-1 flex flex-wrap gap-x-4 gap-y-1">
                  <span>آخر إجراء محقق: <strong className="text-slate-200">{lastVerifiedAction}</strong></span>
                  <span>الخطوة التالية: <strong className="text-indigo-300">{nextPlannedAction}</strong></span>
                </div>
              </div>
            </div>

            {/* AI Router Selection */}
            <div className="flex items-center gap-2 bg-slate-950 p-1 rounded-xl border border-slate-800">
              <span className="text-xs text-slate-400 px-2 font-medium">وضع الذكاء:</span>
              {(['LOCAL_ONLY', 'AUTO', 'CLOUD_PREFERRED'] as AIProviderMode[]).map((mode) => (
                <button
                  key={mode}
                  onClick={() => setAiMode(mode)}
                  className={`px-3 py-1 rounded-lg text-xs font-medium transition ${
                    aiMode === mode
                      ? 'bg-indigo-600 text-white font-semibold shadow-sm'
                      : 'text-slate-400 hover:text-white'
                  }`}
                >
                  {mode === 'LOCAL_ONLY'
                    ? 'محلي فقط'
                    : mode === 'AUTO'
                    ? 'تلقائي (Auto)'
                    : 'سحابي مفضل'}
                </button>
              ))}
            </div>
          </div>
        </div>

        {/* Navigation Tabs */}
        <div className="flex items-center gap-2 border-b border-slate-800 pb-2 overflow-x-auto scrollbar-none">
          {[
            { id: 'overview', label: 'الرئيسية والمشاريع', icon: FolderGit2 },
            { id: 'overlay', label: 'الزر العائم (Overlay)', icon: Layers },
            { id: 'keyboard', label: 'لوحة المفاتيح (IME)', icon: Keyboard },
            { id: 'voice', label: 'التفاعل الصوتي (Voice)', icon: Mic },
            { id: 'tasks', label: 'محرك التحقق والمهام', icon: CheckCircle2 },
            { id: 'remote', label: 'الوكيل البرمجي السحابي (Remote)', icon: Terminal },
            { id: 'rules', label: 'محرك الصلاحيات والقواعد', icon: Shield },
            { id: 'codebase', label: 'ملفات كود أندرويد (Kotlin)', icon: FileCode },
          ].map((tab) => {
            const Icon = tab.icon;
            return (
              <button
                key={tab.id}
                onClick={() => setActiveTab(tab.id as any)}
                className={`flex items-center gap-2 px-4 py-2 rounded-xl text-xs font-semibold whitespace-nowrap transition ${
                  activeTab === tab.id
                    ? 'bg-indigo-600/20 text-indigo-300 border border-indigo-500/40 shadow-sm'
                    : 'text-slate-400 hover:text-slate-200 hover:bg-slate-900 border border-transparent'
                }`}
              >
                <Icon className="w-4 h-4" />
                <span>{tab.label}</span>
              </button>
            );
          })}
        </div>

        {/* Tab Content */}
        {activeTab === 'overview' && (
          <div className="space-y-6">
            {/* Active Project Card */}
            <div className="bg-slate-900 border border-slate-800 rounded-2xl p-5 shadow-lg">
              <div className="flex items-center justify-between pb-3 border-b border-slate-800 mb-4">
                <div>
                  <h3 className="font-bold text-base text-white">المشروع النشط: Todd</h3>
                  <p className="text-xs text-slate-400 mt-0.5">
                    المستودع: <code className="text-indigo-400 font-mono">fateh1989/Todd</code> (فرع main)
                  </p>
                </div>
                <span className="text-xs bg-emerald-950 text-emerald-300 border border-emerald-800 px-3 py-1 rounded-full font-medium">
                  متصل وموثق
                </span>
              </div>

              <div className="grid grid-cols-1 md:grid-cols-3 gap-4 mb-4">
                <div className="bg-slate-950 p-3 rounded-xl border border-slate-800/80">
                  <span className="text-[11px] text-slate-400 block mb-1">الهدف الحالي:</span>
                  <span className="text-xs text-slate-200 font-medium">{projects[0].currentGoal}</span>
                </div>
                <div className="bg-slate-950 p-3 rounded-xl border border-slate-800/80">
                  <span className="text-[11px] text-slate-400 block mb-1">آخر التزام موثق:</span>
                  <span className="text-xs font-mono text-indigo-300">{projects[0].lastVerifiedCommit}</span>
                </div>
                <div className="bg-slate-950 p-3 rounded-xl border border-slate-800/80">
                  <span className="text-[11px] text-slate-400 block mb-1">حالة الذاكرة المحلية (Room):</span>
                  <span className="text-xs text-emerald-400 font-medium">مستقرة ودائمة عبر الجلسات</span>
                </div>
              </div>

              <div className="bg-indigo-950/40 border border-indigo-900/60 rounded-xl p-3 text-xs text-indigo-200 flex items-center justify-between">
                <div className="flex items-center gap-2">
                  <Shield className="w-4 h-4 text-indigo-400 shrink-0" />
                  <span>
                    وفقاً لمواصفات <strong>TODD_MASTER_SPEC.md</strong>، المعمارية بنيت بأكملها لدعم
                    الزر العائم، لوحة المفاتيح IME، الوعي الشاشي، والتنفيذ البعيد دون أي اختصار.
                  </span>
                </div>
              </div>
            </div>

            {/* Quick Task Lifecyle Teaser */}
            <TaskStateMachineView
              tasks={tasks}
              onPlanTask={handlePlanTask}
              onExecuteStep={handleExecuteStep}
              onVerifyTask={handleVerifyTask}
              onCompleteTask={handleCompleteTask}
            />
          </div>
        )}

        {activeTab === 'overlay' && (
          <div className="space-y-4">
            <div className="bg-slate-900/80 border border-slate-800 rounded-xl p-4">
              <h3 className="font-bold text-sm text-white mb-1">محاكي الزر العائم (Floating Todd)</h3>
              <p className="text-xs text-slate-400 leading-relaxed">
                اسحب الزر العائم داخل النافذة التفاعلية أدناه:
                <br />
                • إذا سحبته إلى اليسار بالأسفل (Hide) سيختفي الزر العائم دون إيقاف Todd.
                <br />
                • إذا سحبته إلى اليمين بالأسفل (Power Off)، سيبدأ مؤشر التريث (Dwell Timer) بالامتلاء،
                ولا يتم إيقاف Todd إلا بعد إمساكه لمدة 1.2 ثانية لمنع الإيقاف الخاطئ.
                <br />
                • اضغط نقرة واحدة على الزر لفتح لوحة Todd المصغرة.
              </p>
            </div>
            <FloatingButtonSimulator
              status={globalStatus}
              isPaused={isPaused}
              isPowerOff={isPowerOff}
              isOverlayVisible={isOverlayVisible}
              onHideOverlay={() => setIsOverlayVisible(false)}
              onPowerOff={handlePowerOff}
              onPowerOn={handlePowerOn}
              onTogglePause={handleTogglePause}
              activeProjectName="Todd Core"
              onOpenMainApp={() => setActiveTab('overview')}
            />
          </div>
        )}

        {activeTab === 'keyboard' && (
          <div className="space-y-4">
            <KeyboardSimulator
              aiRouter={aiRouter}
              aiMode={aiMode}
              isPaused={isPaused}
              isPowerOff={isPowerOff}
            />
          </div>
        )}

        {activeTab === 'voice' && (
          <div className="space-y-4">
            <VoiceSimulator
              aiRouter={aiRouter}
              aiMode={aiMode}
              isPaused={isPaused}
              isPowerOff={isPowerOff}
            />
          </div>
        )}

        {activeTab === 'tasks' && (
          <TaskStateMachineView
            tasks={tasks}
            onPlanTask={handlePlanTask}
            onExecuteStep={handleExecuteStep}
            onVerifyTask={handleVerifyTask}
            onCompleteTask={handleCompleteTask}
          />
        )}

        {activeTab === 'remote' && (
          <RemoteCodingSimulator />
        )}

        {activeTab === 'rules' && (
          <RulesEngineViewer />
        )}

        {activeTab === 'codebase' && (
          <AndroidCodebaseViewer />
        )}
      </main>

      {/* Gemini Live Voice Modal */}
      <GeminiLiveVoiceModal
        isOpen={isLiveVoiceOpen}
        onClose={() => setIsLiveVoiceOpen(false)}
        aiMode={aiMode}
        onSwitchMode={(mode) => setAiMode(mode)}
        onToolActionExecuted={(toolName, evidence) => {
          setLastVerifiedAction(`استدعاء أداة صوتي: ${toolName} (${evidence})`);
        }}
      />

      {/* Footer */}
      <footer className="border-t border-slate-900 bg-slate-950 py-4 text-center text-xs text-slate-500 font-mono">
        Todd Personal Agent • Repository: fateh1989/Todd • Spec: TODD_MASTER_SPEC.md (Canonical & Binding)
      </footer>
    </div>
  );
};
