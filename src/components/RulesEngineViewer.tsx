import React, { useState } from 'react';
import { Shield, AlertTriangle, CheckCircle2, Lock, ArrowRight, UserCheck } from 'lucide-react';

interface RuleItem {
  id: string;
  category: string;
  scope: string;
  behavior: 'ALLOW_WITHOUT_ASKING' | 'ALLOW_IF_PREAPPROVED' | 'ASK_BEFORE_ACTION' | 'HAND_OFF_TO_OWNER';
  explanation: string;
}

export const RulesEngineViewer: React.FC = () => {
  const rules: RuleItem[] = [
    {
      id: 'rule-1',
      category: 'قراءة المستودع (GIT_READ)',
      scope: 'GLOBAL',
      behavior: 'ALLOW_WITHOUT_ASKING',
      explanation: 'قراءة ملفات وفروع المستودع والالتزامات مصرح بها تلقائياً دون طلب إذن.',
    },
    {
      id: 'rule-2',
      category: 'إنشاء التزام على فرع ميزة (GIT_COMMIT_FEATURE_BRANCH)',
      scope: 'GLOBAL',
      behavior: 'ALLOW_IF_PREAPPROVED',
      explanation: 'مسموح إذا كانت التعليمات الحالية تتضمن تفويضاً صريحاً بعد اجتياز الفحوصات.',
    },
    {
      id: 'rule-3',
      category: 'دفع التزام إلى فرع رئيسي (GIT_PUSH_MAIN)',
      scope: 'fateh1989/Todd',
      behavior: 'ASK_BEFORE_ACTION',
      explanation: 'يتطلب دائماً عرض تأكيد مسبق يوضح التغييرات والالتزام قبل الإرسال.',
    },
    {
      id: 'rule-4',
      category: 'حذف فرع أو إعادة كتابة التاريخ (GIT_DELETE_BRANCH / FORCE_PUSH)',
      scope: 'GLOBAL',
      behavior: 'HAND_OFF_TO_OWNER',
      explanation: 'عملية تدميرية غير قابلة للتراجع؛ تتطلب التدخل المباشر للمالك ولا يجوز تفويضها.',
    },
    {
      id: 'rule-5',
      category: 'إرسال لقطة شاشة دلالية للسحابة (UPLOAD_SCREENSHOT_CLOUD)',
      scope: 'GLOBAL',
      behavior: 'ASK_BEFORE_ACTION',
      explanation: 'طلب تأكيد المالك مع اقتصار الإرسال على النص الأدنى اللازم فقط.',
    },
  ];

  const [activePrompt, setActivePrompt] = useState<string | null>(null);

  const testAction = (rule: RuleItem) => {
    if (rule.behavior === 'HAND_OFF_TO_OWNER') {
      setActivePrompt(
        `🚨 عملية عالية الخطورة: طلب Todd تنفيذ (${rule.category}). تتطلب هذه العملية موافقة يدوية صريحة ولا يمكن تفويضها للذكاء الاصطناعي.`
      );
    } else if (rule.behavior === 'ASK_BEFORE_ACTION') {
      setActivePrompt(
        `⚠️ استئذان مسبق مطلوب: يرغب Todd في تنفيذ (${rule.category}) على المستودع fateh1989/Todd. هل توافق على التنفيذ؟`
      );
    } else if (rule.behavior === 'ALLOW_IF_PREAPPROVED') {
      setActivePrompt(
        `ℹ️ فحص الموافقة المسبقة: العملية (${rule.category}) تمت الموافقة عليها مسبقاً في سياق المهمة الحالية ونُفّذت بأمان.`
      );
    } else {
      setActivePrompt(
        `✅ تنفيذ تلقائي فوري: العملية (${rule.category}) مسموح بها بدون استئذان.`
      );
    }
  };

  const behaviorBadges: Record<RuleItem['behavior'], { bg: string; text: string; label: string }> = {
    ALLOW_WITHOUT_ASKING: { bg: 'bg-emerald-950/80 border-emerald-800', text: 'text-emerald-300', label: 'تنفيذ تلقائي' },
    ALLOW_IF_PREAPPROVED: { bg: 'bg-cyan-950/80 border-cyan-800', text: 'text-cyan-300', label: 'مسموح بموافقة مسبقة' },
    ASK_BEFORE_ACTION: { bg: 'bg-amber-950/80 border-amber-800', text: 'text-amber-300', label: 'استئذان مسبق' },
    HAND_OFF_TO_OWNER: { bg: 'bg-rose-950/80 border-rose-800', text: 'text-rose-300', label: 'تفويض مباشر للمالك' },
  };

  return (
    <div className="bg-slate-900 border border-slate-800 rounded-2xl p-5 shadow-xl space-y-4">
      <div>
        <h4 className="font-bold text-sm text-white">
          محرك القواعد وصلاحيات التدخل (Permission & Rules Engine)
        </h4>
        <p className="text-xs text-slate-400 mt-0.5">
          المواد 18 و124 و125: أربعة مستويات سلوك تحكم كل أداة أو إجراء خارجي لحماية أمان المالك وبياناته.
        </p>
      </div>

      {activePrompt && (
        <div className="bg-slate-950 border border-indigo-700/60 rounded-xl p-4 flex items-start justify-between gap-3 animate-in fade-in">
          <div className="flex items-start gap-2.5">
            <UserCheck className="w-5 h-5 text-indigo-400 shrink-0 mt-0.5" />
            <p className="text-xs text-slate-200 leading-relaxed font-sans">{activePrompt}</p>
          </div>
          <button
            onClick={() => setActivePrompt(null)}
            className="text-xs text-slate-400 hover:text-white px-2 py-1 bg-slate-900 rounded"
          >
            إغلاق
          </button>
        </div>
      )}

      <div className="space-y-2.5">
        {rules.map((rule) => {
          const badge = behaviorBadges[rule.behavior];
          return (
            <div
              key={rule.id}
              className="bg-slate-950 border border-slate-800 rounded-xl p-3.5 flex flex-col sm:flex-row sm:items-center justify-between gap-3"
            >
              <div className="space-y-1">
                <div className="flex items-center gap-2">
                  <span className="font-bold text-xs text-white">{rule.category}</span>
                  <span className="text-[10px] font-mono bg-slate-900 text-slate-400 px-1.5 py-0.5 rounded">
                    {rule.scope}
                  </span>
                </div>
                <p className="text-xs text-slate-400 leading-relaxed">{rule.explanation}</p>
              </div>

              <div className="flex items-center gap-2 shrink-0">
                <span
                  className={`text-[10px] font-medium px-2 py-1 rounded-lg border ${badge.bg} ${badge.text}`}
                >
                  {badge.label}
                </span>
                <button
                  onClick={() => testAction(rule)}
                  className="px-2.5 py-1 bg-slate-800 hover:bg-slate-700 text-slate-200 rounded-lg text-xs font-medium border border-slate-700"
                >
                  اختبار الإجراء
                </button>
              </div>
            </div>
          );
        })}
      </div>
    </div>
  );
};
