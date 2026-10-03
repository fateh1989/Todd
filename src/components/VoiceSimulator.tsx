import React, { useState, useEffect, useRef } from 'react';
import { Mic, MicOff, Volume2, VolumeX, Sparkles, Square } from 'lucide-react';
import { AIRouter } from '../services/aiService';
import { AIProviderMode } from '../types';

interface VoiceSimulatorProps {
  aiRouter: AIRouter;
  aiMode: AIProviderMode;
  isPaused: boolean;
  isPowerOff: boolean;
}

export const VoiceSimulator: React.FC<VoiceSimulatorProps> = ({
  aiRouter,
  aiMode,
  isPaused,
  isPowerOff,
}) => {
  const [isListening, setIsListening] = useState(false);
  const [isSpeaking, setIsSpeaking] = useState(false);
  const [continuousMode, setContinuousMode] = useState(false);
  const [transcript, setTranscript] = useState('');
  const [lastResponse, setLastResponse] = useState(
    'مرحباً! أنا Todd، مساعدك الهجين. كيف يمكنني مساعدتك اليوم؟'
  );
  const synthRef = useRef<SpeechSynthesis | null>(null);

  useEffect(() => {
    if (typeof window !== 'undefined' && 'speechSynthesis' in window) {
      synthRef.current = window.speechSynthesis;
    }
  }, []);

  const speak = (text: string) => {
    if (!synthRef.current) return;
    synthRef.current.cancel(); // Stop prior speech (barge-in)

    const cleanText = text.replace(/[*#_]/g, '');
    const utterance = new SpeechSynthesisUtterance(cleanText);
    utterance.lang = 'ar-SA';
    utterance.rate = 1.05;

    utterance.onstart = () => setIsSpeaking(true);
    utterance.onend = () => {
      setIsSpeaking(false);
      if (continuousMode && !isPowerOff && !isPaused) {
        // In continuous mode, listen again after speaking
        setIsListening(true);
      }
    };
    utterance.onerror = () => setIsSpeaking(false);

    synthRef.current.speak(utterance);
  };

  const handleInterrupt = () => {
    // Barge-in: immediately stop audio output!
    if (synthRef.current) {
      synthRef.current.cancel();
    }
    setIsSpeaking(false);
    setIsListening(true);
  };

  const togglePushToTalk = async () => {
    if (isPowerOff || isPaused) return;

    if (isListening) {
      // Stop listening and process
      setIsListening(false);
      const userUtterance = transcript.trim() || 'ما هي حالة المشاريع الحالية في Todd؟';
      setTranscript(userUtterance);

      const res = await aiRouter.route(
        `صوتياً: ${userUtterance}`,
        aiMode,
        undefined,
        'جلسة صوتية نشطة'
      );
      setLastResponse(res.text);
      speak(res.text);
    } else {
      if (isSpeaking) {
        handleInterrupt();
      } else {
        setIsListening(true);
        setTranscript('');
      }
    }
  };

  return (
    <div className="bg-slate-900 border border-slate-800 rounded-2xl p-4 shadow-xl">
      <div className="flex items-center justify-between mb-4 pb-2 border-b border-slate-800">
        <div className="flex items-center gap-2">
          <span
            className={`w-2.5 h-2.5 rounded-full ${
              isListening ? 'bg-rose-500 animate-ping' : isSpeaking ? 'bg-indigo-400' : 'bg-slate-500'
            }`}
          />
          <h4 className="font-bold text-sm text-white">التفاعل الصوتي الحي (Voice Interaction)</h4>
        </div>
        <div className="flex items-center gap-2">
          <label className="text-[11px] text-slate-400 flex items-center gap-1.5 cursor-pointer">
            <input
              type="checkbox"
              checked={continuousMode}
              onChange={(e) => setContinuousMode(e.target.checked)}
              className="rounded bg-slate-950 border-slate-700 text-indigo-600 focus:ring-0"
            />
            <span>وضع المحادثة المستمرة</span>
          </label>
        </div>
      </div>

      {/* Spoken Output Display */}
      <div className="bg-slate-950 border border-slate-800/80 rounded-xl p-4 mb-4">
        <div className="flex items-center justify-between text-xs text-slate-400 mb-2">
          <span>استجابة Todd الصوتية:</span>
          {isSpeaking && (
            <span className="flex items-center gap-1.5 text-indigo-400 font-medium animate-pulse">
              <Volume2 className="w-3.5 h-3.5" />
              <span>يتحدث الآن (يمكنك المقاطعة بالضغط أو التحدث)</span>
            </span>
          )}
        </div>
        <p className="text-sm text-slate-200 leading-relaxed font-sans">{lastResponse}</p>
      </div>

      {/* Voice Controls & Visualizer */}
      <div className="flex flex-col items-center justify-center p-4 bg-slate-950/60 rounded-xl border border-slate-800/40">
        {/* Audio Wave Visualizer Simulation */}
        <div className="flex items-center gap-1 h-10 mb-4">
          {[40, 70, 30, 90, 50, 80, 20, 60, 95, 45, 75, 35].map((h, i) => (
            <div
              key={i}
              className={`w-1 rounded-full transition-all duration-150 ${
                isListening
                  ? 'bg-rose-500'
                  : isSpeaking
                  ? 'bg-indigo-500'
                  : 'bg-slate-800 h-2'
              }`}
              style={{
                height: isListening || isSpeaking ? `${Math.max(8, (h * (i % 2 === 0 ? 1 : 0.7)))}%` : '6px',
              }}
            />
          ))}
        </div>

        <div className="flex items-center gap-4">
          {/* Main Mic Button */}
          <button
            onClick={togglePushToTalk}
            disabled={isPowerOff || isPaused}
            className={`w-16 h-16 rounded-full flex items-center justify-center shadow-xl transition-all active:scale-95 disabled:opacity-40 ${
              isListening
                ? 'bg-rose-600 hover:bg-rose-500 text-white animate-pulse shadow-rose-600/50'
                : 'bg-indigo-600 hover:bg-indigo-500 text-white shadow-indigo-600/30'
            }`}
          >
            {isListening ? <MicOff className="w-7 h-7" /> : <Mic className="w-7 h-7" />}
          </button>

          {/* Barge-in / Interrupt Button (Active during speech) */}
          {isSpeaking && (
            <button
              onClick={handleInterrupt}
              className="flex items-center gap-1.5 px-3 py-2 bg-rose-500/20 hover:bg-rose-500/30 border border-rose-500/40 text-rose-300 rounded-xl text-xs font-semibold transition"
            >
              <Square className="w-3.5 h-3.5 fill-rose-300" />
              <span>مقاطعة (Barge-in)</span>
            </button>
          )}
        </div>

        <span className="text-[11px] text-slate-500 mt-3">
          {isListening
            ? 'Todd يستمع إليك الآن... اضغط مجدداً للإرسال'
            : isSpeaking
            ? 'اضغط مقاطعة أو اضغط الميكروفون للتحدث فوراً'
            : 'اضغط الميكروفون للبدء بالحديث بنمط Push-to-Talk'}
        </span>
      </div>
    </div>
  );
};
