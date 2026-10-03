import React, { useState } from 'react';
import { Play, Pause, RefreshCw, AlertTriangle, ShieldCheck, Terminal, Cpu, CheckCircle, Wifi, WifiOff } from 'lucide-react';

interface Iteration {
  index: number;
  commit: string;
  hypothesis: string;
  command: string;
  passed: boolean;
  error?: string;
  evidence?: string;
}

export const RemoteCodingSimulator: React.FC = () => {
  const [jobId, setJobId] = useState('todd-remote-9821');
  const [status, setStatus] = useState<
    'IDLE' | 'RUNNING' | 'WAITING_CI' | 'BLOCKED' | 'VERIFIED' | 'COMPLETED'
  >('RUNNING');
  const [phoneConnected, setPhoneConnected] = useState(true);
  const [stuckCount, setStuckCount] = useState(0);

  const [iterations, setIterations] = useState<Iteration[]>([
    {
      index: 1,
      commit: '5583d33',
      hypothesis: 'تحديث تعريفات gradle dependencies لتطابق Java 17 وCompose BOM',
      command: './gradlew testDebugUnitTest --no-daemon',
      passed: true,
      evidence: 'PASS: 12 tests passed, 0 failures',
    },
    {
      index: 2,
      commit: '5583d33',
      hypothesis: 'بناء حزمة APK وفحص مخرجات مجلد app/build/outputs',
      command: './gradlew assembleDebug --no-daemon',
      passed: true,
      evidence: 'BUILD SUCCESSFUL: artifact generated at app/build/outputs/apk/debug/app-debug.apk',
    },
  ]);

  const handleSimulateNextStep = () => {
    const nextIdx = iterations.length + 1;
    const newIter: Iteration = {
      index: nextIdx,
      commit: '5583d33',
      hypothesis: 'فحص مصفوفة الرموز في ToddInputMethodService لضمان ثبات لوحة المفاتيح',
      command: './gradlew lintDebug',
      passed: true,
      evidence: 'LINT SUCCESS: 0 errors, 0 warnings',
    };
    setIterations([...iterations, newIter]);
    setStatus('VERIFIED');
  };

  const handleTriggerStuckLoop = () => {
    const nextIdx = iterations.length + 1;
    const repeatedError = 'Execution failed for task :app:compileDebugKotlin (Type mismatch: inferred type is String? but String was expected)';
    const failingIter: Iteration = {
      index: nextIdx,
      commit: '5583d33',
      hypothesis: 'محاولة معالجة الخطأ دون تغيير العامل التقني الجذري',
      command: './gradlew assembleDebug',
      passed: false,
      error: repeatedError,
    };

    const newIterations = [...iterations, failingIter];
    setIterations(newIterations);

    const newCount = stuckCount + 1;
    setStuckCount(newCount);

    if (newCount >= 3) {
      // Stuck loop rule from TODD_MASTER_SPEC.md Section 159
      setStatus('BLOCKED');
    }
  };

  const handleReset = () => {
    setStuckCount(0);
    setStatus('RUNNING');
  };

  return (
    <div className="bg-slate-900 border border-slate-800 rounded-2xl p-5 shadow-xl space-y-4">
      {/* Header */}
      <div className="flex flex-col sm:flex-row sm:items-center justify-between pb-3 border-b border-slate-800 gap-3">
        <div>
          <div className="flex items-center gap-2">
            <span className="w-2.5 h-2.5 rounded-full bg-cyan-400 animate-pulse" />
            <h4 className="font-bold text-sm text-white">
              محاكي الوكيل البرمجي البعيد (Remote Cloud Executor)
            </h4>
          </div>
          <p className="text-xs text-slate-400 mt-0.5">
            مفهوم «حاسوب العمل السحابي»: تنفيذ برمجيات طويلة الأمد (ساعات) دون استنزاف بطارية الهاتف أو إبقائه نشطاً.
          </p>
        </div>

        {/* Phone Disconnect / Reconnect Simulator */}
        <div className="flex items-center gap-2">
          <button
            onClick={() => setPhoneConnected(!phoneConnected)}
            className={`flex items-center gap-1.5 px-3 py-1.5 rounded-xl text-xs font-semibold border transition ${
              phoneConnected
                ? 'bg-emerald-950/60 border-emerald-800 text-emerald-300'
                : 'bg-rose-950/60 border-rose-800 text-rose-300 animate-pulse'
            }`}
          >
            {phoneConnected ? <Wifi className="w-3.5 h-3.5" /> : <WifiOff className="w-3.5 h-3.5" />}
            <span>{phoneConnected ? 'الهاتف متصل' : 'الهاتف غير متصل (المهمة مستمرة سحابياً)'}</span>
          </button>
        </div>
      </div>

      {/* Remote Job State Card */}
      <div className="bg-slate-950 border border-slate-800 rounded-xl p-4 grid grid-cols-1 sm:grid-cols-4 gap-3">
        <div>
          <span className="text-[11px] text-slate-400 block mb-1">معرف المهمة البعيدة:</span>
          <span className="text-xs font-mono text-indigo-300 font-bold">{jobId}</span>
        </div>
        <div>
          <span className="text-[11px] text-slate-400 block mb-1">المستودع والفرع:</span>
          <span className="text-xs text-slate-200 font-mono">fateh1989/Todd (main)</span>
        </div>
        <div>
          <span className="text-[11px] text-slate-400 block mb-1">حالة التنفيذ البعيد:</span>
          <span
            className={`text-[11px] font-mono px-2 py-0.5 rounded-md font-bold ${
              status === 'BLOCKED'
                ? 'bg-rose-900/60 text-rose-300 border border-rose-700'
                : status === 'VERIFIED'
                ? 'bg-emerald-900/60 text-emerald-300 border border-emerald-700'
                : 'bg-cyan-900/60 text-cyan-300 border border-cyan-700'
            }`}
          >
            {status}
          </span>
        </div>
        <div>
          <span className="text-[11px] text-slate-400 block mb-1">آخر نبض سحابي (Heartbeat):</span>
          <span className="text-xs text-emerald-400 font-mono">نشط منذ 3 ثوانٍ</span>
        </div>
      </div>

      {/* Stuck Loop Warning (Section 159) */}
      {status === 'BLOCKED' && (
        <div className="bg-rose-950/60 border border-rose-800/80 rounded-xl p-4 flex items-start gap-3">
          <AlertTriangle className="w-5 h-5 text-rose-400 shrink-0 mt-0.5" />
          <div className="space-y-1">
            <h5 className="font-bold text-xs text-rose-200">
              تم اكتشاف حلقة تعليق متكررة (Stuck-Loop Detected)
            </h5>
            <p className="text-xs text-rose-300/80 leading-relaxed">
              وفقاً للمادة 159 من وثيقة المواصفات: تكرار نفس الخطأ 3 مرات متتالية دون تغيير العامل
              التقني يوجب إيقاف المهمة مؤقتاً وحجبها (BLOCKED) لمنع استنزاف الوقت والتكاليف، وطلب خطة
              جديدة من المالك أو ترقية النموذج.
            </p>
            <button
              onClick={handleReset}
              className="mt-2 px-3 py-1 bg-rose-600 hover:bg-rose-500 text-white rounded-lg text-xs font-semibold"
            >
              إعادة تعيين واستئناف بخطة جديدة
            </button>
          </div>
        </div>
      )}

      {/* Iteration Timeline Records */}
      <div className="space-y-2">
        <span className="text-xs font-semibold text-slate-300 block">
          سجل التكرارات البرمجية الملموسة (Iteration Records):
        </span>
        <div className="space-y-2 max-h-[240px] overflow-y-auto pr-1">
          {iterations.map((iter) => (
            <div
              key={iter.index}
              className="bg-slate-950/80 border border-slate-800/80 rounded-xl p-3 text-xs space-y-1.5"
            >
              <div className="flex items-center justify-between">
                <span className="font-bold text-slate-200">
                  التكرار #{iter.index}: {iter.hypothesis}
                </span>
                <span
                  className={`text-[10px] font-mono px-2 py-0.5 rounded ${
                    iter.passed ? 'bg-emerald-950 text-emerald-300' : 'bg-rose-950 text-rose-300'
                  }`}
                >
                  {iter.passed ? 'اجتياز (PASS)' : 'فشل (FAIL)'}
                </span>
              </div>
              <div className="font-mono text-[11px] text-slate-400 bg-slate-900/60 p-2 rounded-lg">
                <span className="text-indigo-400">$ </span>
                {iter.command}
              </div>
              {iter.evidence && (
                <div className="text-emerald-400 text-[11px] flex items-center gap-1">
                  <CheckCircle className="w-3.5 h-3.5 shrink-0" />
                  <span>{iter.evidence}</span>
                </div>
              )}
              {iter.error && (
                <div className="text-rose-400 text-[11px] flex items-start gap-1">
                  <AlertTriangle className="w-3.5 h-3.5 shrink-0 mt-0.5" />
                  <span>{iter.error}</span>
                </div>
              )}
            </div>
          ))}
        </div>
      </div>

      {/* Simulator Control Actions */}
      <div className="flex flex-wrap items-center gap-2 pt-2 border-t border-slate-800">
        <button
          onClick={handleSimulateNextStep}
          disabled={status === 'BLOCKED'}
          className="flex items-center gap-1.5 px-3.5 py-1.5 bg-indigo-600 hover:bg-indigo-500 text-white rounded-xl text-xs font-semibold shadow-sm transition disabled:opacity-40"
        >
          <Play className="w-3.5 h-3.5" />
          <span>محاكاة خطوة برمجية ناجحة</span>
        </button>

        <button
          onClick={handleTriggerStuckLoop}
          className="flex items-center gap-1.5 px-3 py-1.5 bg-amber-600/20 hover:bg-amber-600/30 border border-amber-600/50 text-amber-300 rounded-xl text-xs font-semibold transition"
        >
          <AlertTriangle className="w-3.5 h-3.5" />
          <span>محاكاة خطأ متكرر (اختبار فحص Stuck Loop)</span>
        </button>
      </div>
    </div>
  );
};
