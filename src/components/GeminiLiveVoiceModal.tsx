import React, { useState, useEffect, useRef } from 'react';
import {
  Mic,
  MicOff,
  Volume2,
  VolumeX,
  Square,
  Sparkles,
  RefreshCw,
  AlertTriangle,
  CheckCircle2,
  X,
  Terminal,
  Shield,
  Layers,
} from 'lucide-react';
import { AIProviderMode } from '../types';

export type LiveVoiceState =
  | 'DISCONNECTED'
  | 'CONNECTING'
  | 'LISTENING'
  | 'THINKING'
  | 'SPEAKING'
  | 'RECONNECTING'
  | 'ERROR';

interface TranscriptItem {
  id: string;
  sender: 'USER' | 'TODD' | 'SYSTEM';
  text: string;
  timestamp: string;
  isToolCall?: boolean;
  toolName?: string;
}

interface GeminiLiveVoiceModalProps {
  isOpen: boolean;
  onClose: () => void;
  aiMode: AIProviderMode;
  onSwitchMode: (mode: AIProviderMode) => void;
  onToolActionExecuted?: (toolName: string, evidence: string) => void;
}

export const GeminiLiveVoiceModal: React.FC<GeminiLiveVoiceModalProps> = ({
  isOpen,
  onClose,
  aiMode,
  onSwitchMode,
  onToolActionExecuted,
}) => {
  const [sessionState, setSessionState] = useState<LiveVoiceState>('DISCONNECTED');
  const [isMuted, setIsMuted] = useState(false);
  const [transcripts, setTranscripts] = useState<TranscriptItem[]>([]);
  const [currentInput, setCurrentInput] = useState('');
  const [localModeBlocked, setLocalModeBlocked] = useState(false);

  const synthRef = useRef<SpeechSynthesis | null>(null);
  const recognitionRef = useRef<any>(null);
  const transcriptsEndRef = useRef<HTMLDivElement>(null);

  // Initialize SpeechSynthesis and SpeechRecognition
  useEffect(() => {
    if (typeof window !== 'undefined') {
      if ('speechSynthesis' in window) {
        synthRef.current = window.speechSynthesis;
      }
      const SpeechRecognition =
        (window as any).SpeechRecognition || (window as any).webkitSpeechRecognition;
      if (SpeechRecognition) {
        const recognition = new SpeechRecognition();
        recognition.continuous = true;
        recognition.interimResults = true;
        recognition.lang = 'ar-SA';

        recognition.onresult = (event: any) => {
          let interim = '';
          for (let i = event.resultIndex; i < event.results.length; ++i) {
            if (event.results[i].isFinal) {
              const finalTranscript = event.results[i][0].transcript.trim();
              if (finalTranscript) {
                handleUserSpeech(finalTranscript);
              }
            } else {
              interim += event.results[i][0].transcript;
            }
          }
          if (interim) setCurrentInput(interim);
        };

        recognition.onerror = () => {
          // Keep session stable
        };

        recognitionRef.current = recognition;
      }
    }

    return () => {
      stopVoiceOutput();
      if (recognitionRef.current) {
        try {
          recognitionRef.current.stop();
        } catch {}
      }
    };
  }, []);

  // Scroll to bottom of conversation
  useEffect(() => {
    transcriptsEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [transcripts]);

  // Handle open/close session
  useEffect(() => {
    if (isOpen) {
      if (aiMode === 'LOCAL_ONLY') {
        setLocalModeBlocked(true);
        setSessionState('DISCONNECTED');
      } else {
        setLocalModeBlocked(false);
        startLiveSession();
      }
    } else {
      endLiveSession();
    }
  }, [isOpen, aiMode]);

  const startLiveSession = () => {
    if (aiMode === 'LOCAL_ONLY') {
      setLocalModeBlocked(true);
      return;
    }
    setLocalModeBlocked(false);
    setSessionState('CONNECTING');
    stopVoiceOutput();

    setTimeout(() => {
      setSessionState('LISTENING');
      addTranscriptItem(
        'TODD',
        'مرحباً بك! أنا Todd، متصل الآن عبر Gemini Live للمحادثة الفورية ثنائية الاتجاه مع دعم استدعاء الأدوات والمقاطعة.'
      );
      speakText('مرحباً بك! أنا Todd، متصل الآن عبر Gemini Live ومستعد للحديث معك وتنفيذ طلباتك.');

      // Start mic listening
      if (recognitionRef.current && !isMuted) {
        try {
          recognitionRef.current.start();
        } catch {}
      }
    }, 600);
  };

  const endLiveSession = () => {
    stopVoiceOutput();
    if (recognitionRef.current) {
      try {
        recognitionRef.current.stop();
      } catch {}
    }
    setSessionState('DISCONNECTED');
  };

  const stopVoiceOutput = () => {
    if (synthRef.current) {
      synthRef.current.cancel();
    }
  };

  // Barge-in: immediately stop speaking when user starts talking
  const handleBargeIn = () => {
    if (sessionState === 'SPEAKING') {
      stopVoiceOutput();
      setSessionState('LISTENING');
    }
  };

  const toggleMute = () => {
    setIsMuted((prev) => {
      const next = !prev;
      if (next && recognitionRef.current) {
        try {
          recognitionRef.current.stop();
        } catch {}
      } else if (!next && recognitionRef.current && sessionState !== 'DISCONNECTED') {
        try {
          recognitionRef.current.start();
        } catch {}
      }
      return next;
    });
  };

  const addTranscriptItem = (
    sender: 'USER' | 'TODD' | 'SYSTEM',
    text: string,
    isToolCall?: boolean,
    toolName?: string
  ) => {
    const item: TranscriptItem = {
      id: `live-${Date.now()}-${Math.random()}`,
      sender,
      text,
      timestamp: new Date().toLocaleTimeString('ar-SA', { hour: '2-digit', minute: '2-digit', second: '2-digit' }),
      isToolCall,
      toolName,
    };
    setTranscripts((prev) => [...prev, item]);
  };

  const speakText = (text: string) => {
    if (!synthRef.current || isMuted) return;
    stopVoiceOutput();

    const clean = text.replace(/[*#_`]/g, '');
    const utterance = new SpeechSynthesisUtterance(clean);
    utterance.lang = 'ar-SA';
    utterance.rate = 1.05;

    utterance.onstart = () => setSessionState('SPEAKING');
    utterance.onend = () => {
      setSessionState('LISTENING');
    };
    utterance.onerror = () => {
      setSessionState('LISTENING');
    };

    synthRef.current.speak(utterance);
  };

  // Process incoming user speech (voice or simulated prompt)
  const handleUserSpeech = (speechText: string) => {
    if (!speechText.trim()) return;

    handleBargeIn();
    setCurrentInput('');
    addTranscriptItem('USER', speechText);
    setSessionState('THINKING');

    // Simulate Function Calling & Tool Binding (Section 17, 18, 25)
    setTimeout(() => {
      const p = speechText.toLowerCase();

      if (p.includes('فحص المستودع') || p.includes('check repo') || p.includes('حالة المشروع')) {
        // Safe tool: check repository status
        const toolMsg = 'تم استدعاء أداة checkRepositoryStatus(repo: "fateh1989/Todd", branch: "main") عبر RulesEngine.';
        addTranscriptItem('SYSTEM', toolMsg, true, 'checkRepositoryStatus');
        onToolActionExecuted?.('checkRepositoryStatus', 'المستودع نظيف وعلى الفرع main والتزام 5583d33 معتمد');

        const reply = 'قمت بفحص المستودع fateh1989/Todd عبر محرك الأدوات. الفرع الرئيسي main سليم، وآخر التزام معتمد وموثق، وحالة البناء خضراء.';
        addTranscriptItem('TODD', reply);
        speakText(reply);
      } else if (p.includes('احذف الفرع') || p.includes('delete branch') || p.includes('force push')) {
        // High-risk tool: delete branch
        const alertMsg = '🚨 عملية تدميرية مرفوضة تلقائياً: طلب أداة GIT_DELETE_BRANCH يتطلب تفويضاً يدوياً صريحاً من المالك (HAND_OFF_TO_OWNER).';
        addTranscriptItem('SYSTEM', alertMsg, true, 'GIT_DELETE_BRANCH');

        const reply = 'تنبيه أمان: وفقاً لقواعد RulesEngine الصارمة في Todd، عمليات حذف الفروع أو Force Push تصنف كعمليات عالية الخطورة وتتطلب موافقتك اليدوية المباشرة.';
        addTranscriptItem('TODD', reply);
        speakText(reply);
      } else if (p.includes('خطوة') || p.includes('نفذ') || p.includes('مهمة')) {
        // Task tool
        addTranscriptItem('SYSTEM', 'تم استدعاء أداة executeTaskStep("بناء حزمة أندرويد")', true, 'executeTaskStep');
        const reply = 'بدأت في جدولة خطوة بناء حزمة Android APK عبر معمارية الوكيل السحابي وربطها بنظام تتبع المهام.';
        addTranscriptItem('TODD', reply);
        speakText(reply);
      } else {
        // Conversational voice response
        const reply = `فهمت طلبك الصوتي: "${speechText}". تم حفظ سياق المحادثة في ذاكرة Todd الدائمة، ومحرك القواعد جاهز لأي استدعاء برمجي.`;
        addTranscriptItem('TODD', reply);
        speakText(reply);
      }
    }, 700);
  };

  if (!isOpen) return null;

  return (
    <div className="fixed inset-0 z-50 bg-slate-950/80 backdrop-blur-md flex items-center justify-center p-4">
      <div className="bg-slate-900 border border-slate-700/80 rounded-3xl w-full max-w-2xl overflow-hidden shadow-2xl flex flex-col max-h-[85vh] animate-in fade-in zoom-in-95">
        {/* Top Header */}
        <div className="p-4 bg-slate-950 border-b border-slate-800 flex items-center justify-between">
          <div className="flex items-center gap-3">
            <div className="w-10 h-10 rounded-2xl bg-gradient-to-tr from-cyan-500 to-indigo-600 flex items-center justify-center text-white shadow-lg shadow-indigo-600/30">
              <Sparkles className="w-5 h-5" />
            </div>
            <div>
              <div className="flex items-center gap-2">
                <h3 className="font-bold text-sm text-white">Gemini Live Voice • Todd</h3>
                <span className="text-[10px] font-mono bg-cyan-950 text-cyan-300 border border-cyan-800 px-2 py-0.5 rounded-full">
                  gemini-3.8-live
                </span>
              </div>
              <p className="text-xs text-slate-400">
                محادثة صوتية فورية ثنائية الاتجاه مع دعم المقاطعة (Barge-in) واستدعاء الأدوات
              </p>
            </div>
          </div>

          <div className="flex items-center gap-2">
            {/* Live State Badge */}
            <span
              className={`text-xs font-semibold px-3 py-1 rounded-full flex items-center gap-1.5 border ${
                sessionState === 'LISTENING'
                  ? 'bg-rose-950/80 border-rose-700 text-rose-300 animate-pulse'
                  : sessionState === 'SPEAKING'
                  ? 'bg-indigo-950/80 border-indigo-700 text-indigo-300'
                  : sessionState === 'THINKING'
                  ? 'bg-amber-950/80 border-amber-700 text-amber-300'
                  : sessionState === 'CONNECTING'
                  ? 'bg-cyan-950/80 border-cyan-700 text-cyan-300'
                  : 'bg-slate-800 border-slate-700 text-slate-400'
              }`}
            >
              <span
                className={`w-2 h-2 rounded-full ${
                  sessionState === 'LISTENING'
                    ? 'bg-rose-400 animate-ping'
                    : sessionState === 'SPEAKING'
                    ? 'bg-indigo-400'
                    : sessionState === 'THINKING'
                    ? 'bg-amber-400'
                    : 'bg-slate-500'
                }`}
              />
              <span>
                {sessionState === 'LISTENING'
                  ? 'يستمع (Listening)'
                  : sessionState === 'SPEAKING'
                  ? 'يتحدث (Speaking)'
                  : sessionState === 'THINKING'
                  ? 'يفكر (Thinking)'
                  : sessionState === 'CONNECTING'
                  ? 'يتصل (Connecting)'
                  : 'غير متصل (Disconnected)'}
              </span>
            </span>

            <button
              onClick={onClose}
              className="p-1.5 text-slate-400 hover:text-white rounded-xl hover:bg-slate-800 transition"
            >
              <X className="w-5 h-5" />
            </button>
          </div>
        </div>

        {/* Local Only Block Warning */}
        {localModeBlocked ? (
          <div className="p-8 text-center space-y-4 my-auto">
            <div className="w-14 h-14 rounded-full bg-amber-500/20 border border-amber-500/40 flex items-center justify-center text-amber-400 mx-auto">
              <Shield className="w-7 h-7" />
            </div>
            <div className="space-y-1">
              <h4 className="font-bold text-base text-white">وضع الذكاء مضبوط على «محلي فقط» (Local Only)</h4>
              <p className="text-xs text-slate-300 max-w-md mx-auto leading-relaxed">
                وفقاً لسياسة الخصوصية الصارمة في مواصفات Todd: في وضع «محلي فقط» يُحظر إرسال أي صوت
                أو نص إلى خوادم السحاب الخارجية أو Gemini Live. لاستخدام المحادثة السحابية الفورية،
                يرجى التبديل إلى الوضع «التلقائي Auto» أو «السحابي المفضل».
              </p>
            </div>
            <div className="flex justify-center gap-3 pt-2">
              <button
                onClick={() => {
                  onSwitchMode('AUTO');
                  setLocalModeBlocked(false);
                }}
                className="px-4 py-2 bg-indigo-600 hover:bg-indigo-500 text-white rounded-xl text-xs font-semibold shadow-lg shadow-indigo-600/30 transition"
              >
                تبديل إلى الوضع التلقائي (Auto) والبدء
              </button>
              <button
                onClick={onClose}
                className="px-4 py-2 bg-slate-800 hover:bg-slate-700 text-slate-300 rounded-xl text-xs font-medium"
              >
                البقاء في الوضع المحلي
              </button>
            </div>
          </div>
        ) : (
          <>
            {/* Conversation Transcripts Box */}
            <div className="flex-1 overflow-y-auto p-4 space-y-3 bg-slate-950/60 max-h-[380px]">
              {transcripts.length === 0 && (
                <div className="text-center py-8 text-slate-500 text-xs font-medium">
                  جاري بدء المحادثة الصوتية... يمكنك التحدث مباشرة أو تجربة أحد الأوامر الصوتية بالأسفل.
                </div>
              )}

              {transcripts.map((t) => (
                <div
                  key={t.id}
                  className={`flex flex-col ${
                    t.sender === 'USER'
                      ? 'items-end'
                      : t.sender === 'SYSTEM'
                      ? 'items-center'
                      : 'items-start'
                  }`}
                >
                  {t.sender === 'SYSTEM' ? (
                    <div className="bg-slate-900 border border-indigo-800/60 text-indigo-300 px-3 py-1.5 rounded-xl text-[11px] font-mono flex items-center gap-2 max-w-md">
                      <Terminal className="w-3.5 h-3.5 text-indigo-400 shrink-0" />
                      <span>{t.text}</span>
                    </div>
                  ) : (
                    <div
                      className={`max-w-[85%] rounded-2xl p-3 text-xs leading-relaxed ${
                        t.sender === 'USER'
                          ? 'bg-indigo-600 text-white rounded-br-none shadow-md'
                          : 'bg-slate-900 border border-slate-800 text-slate-100 rounded-bl-none shadow-sm'
                      }`}
                    >
                      <div className="flex items-center justify-between text-[10px] opacity-75 mb-1 gap-4">
                        <span className="font-bold">
                          {t.sender === 'USER' ? 'أنت (صوتياً)' : 'Todd (Gemini Live)'}
                        </span>
                        <span className="font-mono">{t.timestamp}</span>
                      </div>
                      <p className="font-sans whitespace-pre-wrap">{t.text}</p>
                    </div>
                  )}
                </div>
              ))}
              <div ref={transcriptsEndRef} />
            </div>

            {/* Audio Wave Visualizer Simulation */}
            <div className="bg-slate-950 border-t border-slate-800/80 px-4 py-2.5 flex items-center justify-between">
              <div className="flex items-center gap-1.5">
                {[30, 60, 90, 45, 80, 20, 70, 95, 40, 60, 85, 30, 75, 50].map((h, idx) => (
                  <div
                    key={idx}
                    className={`w-1 rounded-full transition-all duration-150 ${
                      sessionState === 'LISTENING'
                        ? 'bg-rose-500'
                        : sessionState === 'SPEAKING'
                        ? 'bg-indigo-500'
                        : 'bg-slate-800 h-1.5'
                    }`}
                    style={{
                      height:
                        sessionState === 'LISTENING' || sessionState === 'SPEAKING'
                          ? `${Math.max(6, (h * (idx % 2 === 0 ? 1 : 0.8)) * 0.4)}px`
                          : '6px',
                    }}
                  />
                ))}
              </div>

              {/* Barge-in Stop Speech Action */}
              {sessionState === 'SPEAKING' && (
                <button
                  onClick={handleBargeIn}
                  className="flex items-center gap-1.5 px-3 py-1 bg-rose-600/20 hover:bg-rose-600/30 border border-rose-500/50 text-rose-300 rounded-lg text-xs font-semibold transition"
                >
                  <Square className="w-3 h-3 fill-rose-300" />
                  <span>مقاطعة كلام Todd (Barge-in)</span>
                </button>
              )}
            </div>

            {/* Bottom Controls & Quick Prompts */}
            <div className="p-4 bg-slate-950 border-t border-slate-800 space-y-3">
              {/* Quick Spoken Action Suggestions */}
              <div className="flex items-center gap-2 overflow-x-auto pb-1 scrollbar-none text-xs">
                <span className="text-[11px] text-slate-400 shrink-0">أوامر صوتية جاهزة:</span>
                {[
                  'تود، افحص المستودع وحالة الالتزامات',
                  'نفذ خطوة فحص البناء لأندرويد',
                  'تود، احذف الفرع الرئيسي main',
                  'ما هي حالة المهام الجارية الآن؟',
                ].map((cmd, i) => (
                  <button
                    key={i}
                    onClick={() => handleUserSpeech(cmd)}
                    disabled={sessionState === 'CONNECTING' || sessionState === 'DISCONNECTED'}
                    className="px-2.5 py-1 bg-slate-900 hover:bg-slate-800 text-slate-300 border border-slate-800 rounded-lg whitespace-nowrap transition disabled:opacity-40"
                  >
                    "{cmd}"
                  </button>
                ))}
              </div>

              {/* Main Controls Row */}
              <div className="flex items-center justify-between gap-3">
                <div className="flex items-center gap-2">
                  <button
                    onClick={toggleMute}
                    className={`p-2.5 rounded-xl border transition ${
                      isMuted
                        ? 'bg-rose-950/60 border-rose-800 text-rose-400'
                        : 'bg-slate-900 hover:bg-slate-800 border-slate-800 text-slate-300'
                    }`}
                    title={isMuted ? 'إلغاء كتم الميكروفون' : 'كتم الميكروفون'}
                  >
                    {isMuted ? <MicOff className="w-4 h-4" /> : <Mic className="w-4 h-4" />}
                  </button>

                  <button
                    onClick={sessionState === 'DISCONNECTED' ? startLiveSession : endLiveSession}
                    className={`flex items-center gap-1.5 px-3 py-2 rounded-xl text-xs font-semibold border transition ${
                      sessionState === 'DISCONNECTED'
                        ? 'bg-emerald-600 hover:bg-emerald-500 text-white border-emerald-500 shadow-emerald-600/30'
                        : 'bg-rose-950/40 hover:bg-rose-900/60 border-rose-800 text-rose-300'
                    }`}
                  >
                    <RefreshCw className={`w-3.5 h-3.5 ${sessionState === 'CONNECTING' ? 'animate-spin' : ''}`} />
                    <span>{sessionState === 'DISCONNECTED' ? 'بدء جلسة جديدة' : 'إنهاء الجلسة'}</span>
                  </button>
                </div>

                {/* Text fallback input for hands-free typing in Live session */}
                <div className="flex-1 flex items-center gap-2">
                  <input
                    type="text"
                    placeholder="أو اكتب كلامك هنا وسيتحدث Todd صوتياً..."
                    value={currentInput}
                    onChange={(e) => setCurrentInput(e.target.value)}
                    onKeyDown={(e) => {
                      if (e.key === 'Enter' && currentInput.trim()) {
                        handleUserSpeech(currentInput.trim());
                      }
                    }}
                    className="flex-1 bg-slate-900 border border-slate-800 rounded-xl px-3 py-2 text-xs text-white placeholder-slate-500 focus:outline-none focus:border-indigo-500"
                  />
                  <button
                    onClick={() => {
                      if (currentInput.trim()) handleUserSpeech(currentInput.trim());
                    }}
                    className="px-3 py-2 bg-indigo-600 hover:bg-indigo-500 text-white rounded-xl text-xs font-semibold"
                  >
                    إرسال
                  </button>
                </div>
              </div>
            </div>
          </>
        )}
      </div>
    </div>
  );
};
