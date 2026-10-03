import React, { useState } from 'react';
import { Task, TaskStatus } from '../types';
import { CheckCircle2, AlertTriangle, Play, ShieldCheck, ArrowRight, XCircle } from 'lucide-react';

interface TaskStateMachineViewProps {
  tasks: Task[];
  onPlanTask: (title: string, goal: string, criteria: string) => void;
  onExecuteStep: (taskId: string) => void;
  onVerifyTask: (taskId: string, isSuccess: boolean, evidence: string) => void;
  onCompleteTask: (taskId: string) => void;
}

export const TaskStateMachineView: React.FC<TaskStateMachineViewProps> = ({
  tasks,
  onPlanTask,
  onExecuteStep,
  onVerifyTask,
  onCompleteTask,
}) => {
  const [newTitle, setNewTitle] = useState('');
  const [newGoal, setNewGoal] = useState('');
  const [newCriteria, setNewCriteria] = useState('');
  const [evidenceInput, setEvidenceInput] = useState('APK build artifact: app-debug.apk verified');

  const handleCreate = (e: React.FormEvent) => {
    e.preventDefault();
    if (!newTitle.trim()) return;
    onPlanTask(
      newTitle,
      newGoal || 'تنفيذ وفحص المطلوب برمجياً',
      newCriteria || 'اجتياز الفحص التجريبي وتوفر الدليل'
    );
    setNewTitle('');
    setNewGoal('');
    setNewCriteria('');
  };

  const statusColors: Record<TaskStatus, string> = {
    PLANNED: 'bg-slate-700 text-slate-200 border-slate-600',
    IN_PROGRESS: 'bg-indigo-900/60 text-indigo-300 border-indigo-700',
    WAITING: 'bg-amber-900/60 text-amber-300 border-amber-700',
    EXECUTED: 'bg-cyan-900/60 text-cyan-300 border-cyan-700',
    VERIFYING: 'bg-purple-900/60 text-purple-300 border-purple-700',
    VERIFIED: 'bg-emerald-900/60 text-emerald-300 border-emerald-600',
    FAILED: 'bg-rose-900/60 text-rose-300 border-rose-700',
    BLOCKED: 'bg-orange-900/60 text-orange-300 border-orange-700',
    PAUSED: 'bg-amber-900/60 text-amber-300 border-amber-700',
    CANCELLED: 'bg-slate-800 text-slate-400 border-slate-700',
    COMPLETED: 'bg-teal-900/60 text-teal-200 border-teal-600',
  };

  return (
    <div className="bg-slate-900 border border-slate-800 rounded-2xl p-4 shadow-xl">
      <div className="flex items-center justify-between mb-4 pb-2 border-b border-slate-800">
        <div>
          <h4 className="font-bold text-sm text-white">
            دورة حياة المهام والتحقق الإلزامي (Verification Engine)
          </h4>
          <p className="text-xs text-slate-400 mt-0.5">
            القاعدة الصارمة: «EXECUTED لا تعني إطلاقاً VERIFIED»، ولا تكتمل المهمة دون دليل مثبت.
          </p>
        </div>
      </div>

      {/* Plan New Task Form */}
      <form onSubmit={handleCreate} className="bg-slate-950 border border-slate-800 rounded-xl p-3.5 mb-5">
        <span className="text-xs font-semibold text-slate-300 block mb-2">تخطيط مهمة جديدة (PLAN):</span>
        <div className="grid grid-cols-1 md:grid-cols-3 gap-2 mb-2">
          <input
            type="text"
            placeholder="عنوان المهمة (مثلاً: بناء حزمة APK لأندرويد)"
            value={newTitle}
            onChange={(e) => setNewTitle(e.target.value)}
            className="bg-slate-900 border border-slate-800 rounded-lg px-3 py-1.5 text-xs text-white focus:outline-none focus:border-indigo-500"
          />
          <input
            type="text"
            placeholder="الهدف الأساسي"
            value={newGoal}
            onChange={(e) => setNewGoal(e.target.value)}
            className="bg-slate-900 border border-slate-800 rounded-lg px-3 py-1.5 text-xs text-white focus:outline-none focus:border-indigo-500"
          />
          <input
            type="text"
            placeholder="معيار الاكتمال المطلوب إثباته"
            value={newCriteria}
            onChange={(e) => setNewCriteria(e.target.value)}
            className="bg-slate-900 border border-slate-800 rounded-lg px-3 py-1.5 text-xs text-white focus:outline-none focus:border-indigo-500"
          />
        </div>
        <button
          type="submit"
          className="px-4 py-1.5 bg-indigo-600 hover:bg-indigo-500 text-white rounded-lg text-xs font-semibold shadow-sm transition"
        >
          تثبيت الخطة (PLAN TASK)
        </button>
      </form>

      {/* Tasks List */}
      <div className="space-y-3">
        {tasks.map((task) => (
          <div
            key={task.id}
            className="bg-slate-950 border border-slate-800/80 rounded-xl p-3.5 hover:border-slate-700 transition"
          >
            <div className="flex items-start justify-between gap-3 mb-2">
              <div>
                <span className="font-bold text-sm text-white block">{task.title}</span>
                <span className="text-xs text-slate-400 block mt-0.5">الهدف: {task.goal}</span>
              </div>
              <span
                className={`text-[10px] font-mono px-2 py-0.5 rounded-full border ${
                  statusColors[task.status]
                }`}
              >
                {task.status}
              </span>
            </div>

            <div className="text-xs text-slate-300 bg-slate-900/60 rounded-lg p-2.5 mb-3 border border-slate-800/40">
              <div className="font-medium text-slate-400 text-[11px] mb-1">الخطوة الحالية:</div>
              <div>{task.currentStep}</div>
              {task.lastEvidence && (
                <div className="mt-2 text-emerald-400 text-[11px] flex items-center gap-1.5">
                  <CheckCircle2 className="w-3.5 h-3.5 shrink-0" />
                  <span>دليل التحقق المثبت: {task.lastEvidence}</span>
                </div>
              )}
              {task.failureCause && (
                <div className="mt-2 text-rose-400 text-[11px] flex items-center gap-1.5">
                  <AlertTriangle className="w-3.5 h-3.5 shrink-0" />
                  <span>سبب الفشل المسجل: {task.failureCause}</span>
                </div>
              )}
            </div>

            {/* Action Buttons for State Transitions */}
            <div className="flex flex-wrap items-center gap-2 pt-1 border-t border-slate-900">
              {task.status === 'PLANNED' && (
                <button
                  onClick={() => onExecuteStep(task.id)}
                  className="flex items-center gap-1 px-3 py-1 bg-indigo-600 hover:bg-indigo-500 text-white rounded-lg text-xs font-medium"
                >
                  <Play className="w-3 h-3" />
                  <span>تنفيذ الخطوة (EXECUTE)</span>
                </button>
              )}

              {task.status === 'IN_PROGRESS' && (
                <button
                  onClick={() => onExecuteStep(task.id)}
                  className="flex items-center gap-1 px-3 py-1 bg-cyan-600 hover:bg-cyan-500 text-white rounded-lg text-xs font-medium"
                >
                  <ArrowRight className="w-3 h-3" />
                  <span>تأكيد التنفيذ المبدئي (MARK EXECUTED)</span>
                </button>
              )}

              {task.status === 'EXECUTED' && (
                <div className="flex items-center gap-2 w-full mt-1">
                  <input
                    type="text"
                    value={evidenceInput}
                    onChange={(e) => setEvidenceInput(e.target.value)}
                    placeholder="أدخل الدليل الملموس..."
                    className="flex-1 bg-slate-900 border border-slate-800 rounded-lg px-2.5 py-1 text-xs text-white focus:outline-none"
                  />
                  <button
                    onClick={() => onVerifyTask(task.id, true, evidenceInput)}
                    className="flex items-center gap-1 px-3 py-1 bg-emerald-600 hover:bg-emerald-500 text-white rounded-lg text-xs font-medium shrink-0"
                  >
                    <ShieldCheck className="w-3 h-3" />
                    <span>إثبات التحقق (VERIFY)</span>
                  </button>
                  <button
                    onClick={() => onVerifyTask(task.id, false, 'فشل اختبار السلامة')}
                    className="flex items-center gap-1 px-2.5 py-1 bg-rose-600/30 hover:bg-rose-600/50 text-rose-300 rounded-lg text-xs font-medium shrink-0 border border-rose-600/40"
                  >
                    <XCircle className="w-3 h-3" />
                    <span>فشل الفحص</span>
                  </button>
                </div>
              )}

              {task.status === 'VERIFIED' && (
                <button
                  onClick={() => onCompleteTask(task.id)}
                  className="flex items-center gap-1 px-3.5 py-1 bg-teal-600 hover:bg-teal-500 text-white rounded-lg text-xs font-semibold shadow-sm"
                >
                  <CheckCircle2 className="w-3.5 h-3.5" />
                  <span>اعتماد الإنجاز النهائي (COMPLETED)</span>
                </button>
              )}
            </div>
          </div>
        ))}
      </div>
    </div>
  );
};
