import React, { useState } from 'react';
import { Sparkles, Check, Globe, CornerDownLeft, Delete } from 'lucide-react';
import { AIRouter } from '../services/aiService';
import { AIProviderMode } from '../types';

interface KeyboardSimulatorProps {
  aiRouter: AIRouter;
  aiMode: AIProviderMode;
  isPaused: boolean;
  isPowerOff: boolean;
}

export const KeyboardSimulator: React.FC<KeyboardSimulatorProps> = ({
  aiRouter,
  aiMode,
  isPaused,
  isPowerOff,
}) => {
  const [lang, setLang] = useState<'ar' | 'en'>('ar');
  const [inputValue, setInputValue] = useState(
    'هذا نموذج لتجربة لوحة مفاتيح Todd على أندرويد مع المعالجة الذكية للنصوص'
  );
  const [isProcessing, setIsProcessing] = useState(false);
  const [lastAction, setLastAction] = useState<string | null>(null);

  const arabicKeys = [
    ['ض', 'ص', 'ث', 'ق', 'ف', 'غ', 'ع', 'ه', 'خ', 'ح', 'ج', 'د'],
    ['ش', 'س', 'ي', 'ب', 'ل', 'ا', 'ت', 'ن', 'م', 'ك', 'ط'],
    ['ئ', 'ء', 'ؤ', 'ر', 'لا', 'ى', 'ة', 'و', 'ز', 'ظ'],
  ];

  const englishKeys = [
    ['Q', 'W', 'E', 'R', 'T', 'Y', 'U', 'I', 'O', 'P'],
    ['A', 'S', 'D', 'F', 'G', 'H', 'J', 'K', 'L'],
    ['Z', 'X', 'C', 'V', 'B', 'N', 'M'],
  ];

  const handleKeyPress = (char: string) => {
    setInputValue((prev) => prev + char);
  };

  const handleBackspace = () => {
    setInputValue((prev) => prev.slice(0, -1));
  };

  const handleSpace = () => {
    setInputValue((prev) => prev + ' ');
  };

  const handleEnter = () => {
    setInputValue((prev) => prev + '\n');
  };

  const handleAIAction = async (action: string) => {
    if (isPowerOff) {
      alert('Todd في حالة إيقاف تام (Power Off). يمكنك تشغيله من التطبيق الرئيسي.');
      return;
    }
    if (isPaused) {
      alert('Todd في حالة إيقاف مؤقت (PAUSED). قم باستئنافه للاستفادة من المعالجة الذكية.');
      return;
    }
    if (!inputValue.trim()) return;

    setIsProcessing(true);
    setLastAction(action);

    try {
      const response = await aiRouter.route(
        `${action}: ${inputValue}`,
        aiMode,
        inputValue,
        'Todd Keyboard Session'
      );
      setInputValue(response.text);
    } catch {
      // Typing continues without breaking
    } finally {
      setIsProcessing(false);
    }
  };

  return (
    <div className="bg-slate-900 border border-slate-800 rounded-2xl p-4 shadow-xl">
      <div className="flex items-center justify-between mb-3 pb-2 border-b border-slate-800">
        <div className="flex items-center gap-2">
          <span className="w-2 h-2 rounded-full bg-emerald-400" />
          <h4 className="font-bold text-sm text-white">محاكي لوحة مفاتيح أندرويد (Todd IME)</h4>
        </div>
        <span className="text-[11px] text-slate-400 font-mono">InputMethodService</span>
      </div>

      {/* Target Input Field (Host App Simulation) */}
      <div className="mb-4">
        <label className="block text-xs text-slate-400 mb-1">
          الحقل النشط في تطبيق المستضيف (WhatsApp, Notes, Mail):
        </label>
        <textarea
          value={inputValue}
          onChange={(e) => setInputValue(e.target.value)}
          rows={3}
          dir={lang === 'ar' ? 'rtl' : 'ltr'}
          className="w-full bg-slate-950 border border-slate-800 rounded-xl p-3 text-sm text-white focus:outline-none focus:border-indigo-500 font-sans"
        />
      </div>

      {/* Todd Keyboard Shell */}
      <div className="bg-slate-950 border border-slate-800/80 rounded-xl p-2.5">
        {/* Todd AI Action Toolbar */}
        <div className="flex items-center gap-1.5 overflow-x-auto pb-2 mb-2 scrollbar-none border-b border-slate-800/60">
          <button
            onClick={() => handleAIAction('ask')}
            disabled={isProcessing}
            className="flex items-center gap-1 px-3 py-1 bg-gradient-to-r from-indigo-600 to-purple-600 text-white rounded-lg text-xs font-semibold shrink-0 shadow-sm hover:opacity-90 disabled:opacity-50"
          >
            <Sparkles className="w-3.5 h-3.5" />
            <span>Todd AI</span>
          </button>

          {[
            { label: 'صوّب (Correct)', action: 'correct' },
            { label: 'أعد صياغة (Rewrite)', action: 'rewrite' },
            { label: 'ترجم (Translate)', action: 'translate' },
            { label: 'لخّص (Summarize)', action: 'summarize' },
            { label: 'ردّ (Reply)', action: 'reply' },
          ].map((item) => (
            <button
              key={item.action}
              onClick={() => handleAIAction(item.action)}
              disabled={isProcessing}
              className="px-2.5 py-1 bg-slate-800 hover:bg-slate-700 text-slate-200 rounded-lg text-xs font-medium shrink-0 border border-slate-700/60 transition disabled:opacity-50"
            >
              {item.label}
            </button>
          ))}

          {isProcessing && (
            <span className="text-[11px] text-indigo-400 font-medium animate-pulse shrink-0 px-2">
              جاري المعالجة...
            </span>
          )}
        </div>

        {/* Keyboard Keys Layout */}
        <div className="flex flex-col gap-1.5 select-none" dir="ltr">
          {(lang === 'ar' ? arabicKeys : englishKeys).map((row, rIdx) => (
            <div key={rIdx} className="flex justify-center gap-1">
              {row.map((k) => (
                <button
                  key={k}
                  onClick={() => handleKeyPress(k)}
                  className="flex-1 max-w-[42px] h-10 bg-slate-900 hover:bg-slate-800 active:bg-indigo-600 text-white rounded-lg text-sm font-medium flex items-center justify-center border border-slate-800 transition active:scale-95"
                >
                  {k}
                </button>
              ))}
            </div>
          ))}

          {/* Bottom Control Row */}
          <div className="flex gap-1.5 mt-1">
            <button
              onClick={() => setLang(lang === 'ar' ? 'en' : 'ar')}
              className="px-3 h-10 bg-slate-800 hover:bg-slate-700 text-white rounded-lg text-xs font-semibold flex items-center gap-1 border border-slate-700"
            >
              <Globe className="w-3.5 h-3.5" />
              <span>{lang === 'ar' ? 'English' : 'عربي'}</span>
            </button>

            <button
              onClick={handleSpace}
              className="flex-1 h-10 bg-slate-900 hover:bg-slate-800 active:bg-slate-750 text-slate-300 rounded-lg text-xs font-medium flex items-center justify-center border border-slate-800"
            >
              مسافة (Space)
            </button>

            <button
              onClick={handleBackspace}
              className="px-3.5 h-10 bg-slate-800 hover:bg-slate-700 active:bg-rose-600/40 text-rose-300 rounded-lg flex items-center justify-center border border-slate-700"
            >
              <Delete className="w-4 h-4" />
            </button>

            <button
              onClick={handleEnter}
              className="px-4 h-10 bg-indigo-600 hover:bg-indigo-500 active:bg-indigo-700 text-white rounded-lg flex items-center justify-center font-medium shadow-sm"
            >
              <CornerDownLeft className="w-4 h-4" />
            </button>
          </div>
        </div>
      </div>
    </div>
  );
};
