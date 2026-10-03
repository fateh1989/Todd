import React, { useState, useRef, useEffect } from 'react';
import { ToddGlobalStatus } from '../types';
import { EyeOff, Power, Sparkles, Mic, Monitor, ArrowUpRight, Play, Pause } from 'lucide-react';

interface FloatingButtonSimulatorProps {
  status: ToddGlobalStatus;
  isPaused: boolean;
  isPowerOff: boolean;
  isOverlayVisible: boolean;
  onHideOverlay: () => void;
  onPowerOff: () => void;
  onPowerOn: () => void;
  onTogglePause: () => void;
  activeProjectName: string;
  onOpenMainApp: () => void;
}

export const FloatingButtonSimulator: React.FC<FloatingButtonSimulatorProps> = ({
  status,
  isPaused,
  isPowerOff,
  isOverlayVisible,
  onHideOverlay,
  onPowerOff,
  onPowerOn,
  onTogglePause,
  activeProjectName,
  onOpenMainApp,
}) => {
  const containerRef = useRef<HTMLDivElement>(null);
  const [pos, setPos] = useState({ x: 280, y: 180 });
  const [isDragging, setIsDragging] = useState(false);
  const [dragStart, setDragStart] = useState({ x: 0, y: 0 });
  const [panelOpen, setPanelOpen] = useState(false);
  const [dwellProgress, setDwellProgress] = useState(0);
  const [hoverTarget, setHoverTarget] = useState<'none' | 'hide' | 'powerOff'>('none');
  const dwellTimerRef = useRef<any>(null);

  const startDrag = (e: React.MouseEvent | React.TouchEvent) => {
    if (isPowerOff) return;
    const clientX = 'touches' in e ? e.touches[0].clientX : e.clientX;
    const clientY = 'touches' in e ? e.touches[0].clientY : e.clientY;
    setIsDragging(true);
    setDragStart({ x: clientX - pos.x, y: clientY - pos.y });
  };

  useEffect(() => {
    const handleMove = (e: MouseEvent | TouchEvent) => {
      if (!isDragging || !containerRef.current) return;
      const clientX = 'touches' in e ? e.touches[0].clientX : e.clientX;
      const clientY = 'touches' in e ? e.touches[0].clientY : e.clientY;

      const rect = containerRef.current.getBoundingClientRect();
      const newX = Math.max(10, Math.min(rect.width - 60, clientX - dragStart.x));
      const newY = Math.max(10, Math.min(rect.height - 60, clientY - dragStart.y));

      setPos({ x: newX, y: newY });

      // Check hover over bottom targets
      if (newY > rect.height - 110) {
        if (newX < rect.width / 2) {
          setHoverTarget('hide');
        } else {
          setHoverTarget('powerOff');
        }
      } else {
        setHoverTarget('none');
      }
    };

    const handleEnd = () => {
      if (!isDragging || !containerRef.current) return;
      setIsDragging(false);

      const rect = containerRef.current.getBoundingClientRect();

      // Trigger target action if hovered
      if (hoverTarget === 'hide') {
        onHideOverlay();
        setPanelOpen(false);
      } else if (hoverTarget === 'powerOff' && dwellProgress >= 100) {
        onPowerOff();
        setPanelOpen(false);
      } else {
        // Magnetic edge snap
        const snapX = pos.x < rect.width / 2 ? 16 : rect.width - 64;
        setPos((p) => ({ ...p, x: snapX }));
      }

      setHoverTarget('none');
      setDwellProgress(0);
      if (dwellTimerRef.current) clearInterval(dwellTimerRef.current);
    };

    if (isDragging) {
      window.addEventListener('mousemove', handleMove);
      window.addEventListener('mouseup', handleEnd);
      window.addEventListener('touchmove', handleMove);
      window.addEventListener('touchend', handleEnd);
    }

    return () => {
      window.removeEventListener('mousemove', handleMove);
      window.removeEventListener('mouseup', handleEnd);
      window.removeEventListener('touchmove', handleMove);
      window.removeEventListener('touchend', handleEnd);
    };
  }, [isDragging, dragStart, hoverTarget, dwellProgress, onHideOverlay, onPowerOff, pos.x]);

  // Handle Power Off dwell timer (hold for 1.2s)
  useEffect(() => {
    if (hoverTarget === 'powerOff') {
      const startTime = Date.now();
      dwellTimerRef.current = setInterval(() => {
        const elapsed = Date.now() - startTime;
        const p = Math.min(100, Math.round((elapsed / 1200) * 100));
        setDwellProgress(p);
        if (p >= 100) {
          clearInterval(dwellTimerRef.current);
          if (navigator.vibrate) navigator.vibrate(50);
        }
      }, 50);
    } else {
      setDwellProgress(0);
      if (dwellTimerRef.current) clearInterval(dwellTimerRef.current);
    }

    return () => {
      if (dwellTimerRef.current) clearInterval(dwellTimerRef.current);
    };
  }, [hoverTarget]);

  if (!isOverlayVisible && !isPowerOff) {
    return (
      <div className="bg-slate-900/90 border border-slate-800 rounded-xl p-4 text-center">
        <p className="text-sm text-slate-400 mb-2">تم إخفاء الزر العائم (Todd لا يزال يعمل في الخلفية)</p>
        <button
          onClick={onHideOverlay}
          className="px-4 py-2 bg-indigo-600 hover:bg-indigo-500 text-white rounded-lg text-xs font-medium transition"
        >
          إعادة إظهار الزر العائم
        </button>
      </div>
    );
  }

  return (
    <div
      ref={containerRef}
      className="relative w-full h-[360px] bg-slate-950/70 border border-slate-800 rounded-2xl overflow-hidden shadow-2xl select-none"
    >
      {/* Background Device Surface */}
      <div className="absolute inset-0 bg-radial from-slate-900/40 to-slate-950 flex flex-col justify-between p-4 pointer-events-none opacity-40">
        <div className="flex justify-between text-[11px] text-slate-400 font-mono">
          <span>Android Mock Overlay Window</span>
          <span>1080 × 2400 (DPI 420)</span>
        </div>
        <div className="text-center text-xs text-slate-500 font-medium">
          اسحب الزر العائم بحرية لاختبار الإفلات، الجذب للحواف، وأهداف الإخفاء والإيقاف النهائي
        </div>
      </div>

      {/* Drag Targets (Visible when dragging) */}
      <div
        className={`absolute bottom-3 inset-x-4 flex gap-4 transition-all duration-300 ${
          isDragging ? 'opacity-100 translate-y-0' : 'opacity-0 translate-y-6 pointer-events-none'
        }`}
      >
        {/* Hide Target */}
        <div
          className={`flex-1 flex items-center justify-center gap-2 py-3 rounded-xl border font-medium text-xs transition-all ${
            hoverTarget === 'hide'
              ? 'bg-amber-500/30 border-amber-400 text-amber-200 scale-105 shadow-lg shadow-amber-500/20'
              : 'bg-slate-900/80 border-slate-700 text-slate-400'
          }`}
        >
          <EyeOff className="w-4 h-4" />
          <span>إخفاء الزر فقط (Hide)</span>
        </div>

        {/* Power Off Target with Dwell Progress */}
        <div
          className={`flex-1 relative flex items-center justify-center gap-2 py-3 rounded-xl border font-medium text-xs transition-all overflow-hidden ${
            hoverTarget === 'powerOff'
              ? 'bg-rose-500/30 border-rose-500 text-rose-100 scale-105 shadow-lg shadow-rose-500/30'
              : 'bg-slate-900/80 border-slate-700 text-slate-400'
          }`}
        >
          {hoverTarget === 'powerOff' && (
            <div
              className="absolute left-0 top-0 bottom-0 bg-rose-600/40 transition-all duration-75"
              style={{ width: `${dwellProgress}%` }}
            />
          )}
          <Power className="w-4 h-4 text-rose-400 relative z-10" />
          <span className="relative z-10">
            {hoverTarget === 'powerOff' && dwellProgress < 100
              ? `إيقاف Todd (${dwellProgress}%)`
              : hoverTarget === 'powerOff'
              ? 'أفلت للإيقاف التام'
              : 'إيقاف تام (Power Off)'}
          </span>
        </div>
      </div>

      {/* Floating Todd Button */}
      {!isPowerOff ? (
        <div
          onMouseDown={startDrag}
          onTouchStart={startDrag}
          onClick={() => !isDragging && setPanelOpen(!panelOpen)}
          style={{ transform: `translate(${pos.x}px, ${pos.y}px)` }}
          className={`absolute top-0 left-0 w-14 h-14 rounded-full cursor-grab active:cursor-grabbing flex items-center justify-center shadow-xl transition-shadow ${
            isDragging ? 'shadow-indigo-500/50 scale-110' : 'hover:scale-105'
          } ${
            isPaused
              ? 'bg-amber-500 border-2 border-amber-300'
              : status === 'WORKING'
              ? 'bg-indigo-600 border-2 border-indigo-400 animate-pulse'
              : 'bg-gradient-to-br from-indigo-500 to-purple-600 border-2 border-white/20'
          }`}
        >
          <div className="flex flex-col items-center justify-center text-white">
            <Sparkles className="w-6 h-6" />
            <span className="text-[9px] font-bold tracking-tight">TODD</span>
          </div>

          {/* Status Indicator Dot */}
          <span
            className={`absolute top-1 right-1 w-3 h-3 rounded-full border-2 border-slate-950 ${
              isPaused
                ? 'bg-amber-400'
                : status === 'WORKING'
                ? 'bg-cyan-400 animate-ping'
                : 'bg-emerald-400'
            }`}
          />
        </div>
      ) : (
        <div className="absolute inset-0 flex flex-col items-center justify-center p-6 bg-slate-950/95 z-30">
          <div className="w-14 h-14 rounded-full bg-rose-500/20 border border-rose-500/50 flex items-center justify-center text-rose-400 mb-3">
            <Power className="w-7 h-7" />
          </div>
          <h3 className="font-bold text-white text-base mb-1">Todd في حالة إيقاف تام (OFF)</h3>
          <p className="text-xs text-slate-400 text-center max-w-sm mb-4">
            تم حفظ حالة المهام والجلسات بأمان وإيقاف استهلاك الموارد. يمكنك تشغيل Todd مجدداً في أي وقت.
          </p>
          <button
            onClick={onPowerOn}
            className="flex items-center gap-2 px-5 py-2.5 bg-emerald-600 hover:bg-emerald-500 text-white rounded-xl text-xs font-semibold shadow-lg shadow-emerald-600/30 transition"
          >
            <Play className="w-4 h-4 fill-white" />
            تشغيل Todd الآن
          </button>
        </div>
      )}

      {/* Compact Assistant Panel (Single Tap Surface) */}
      {panelOpen && !isPowerOff && (
        <div
          style={{
            transform: `translate(${Math.max(10, Math.min(pos.x - 100, 160))}px, ${Math.min(
              pos.y + 64,
              180
            )}px)`,
          }}
          className="absolute top-0 left-0 w-72 bg-slate-900 border border-slate-700/80 rounded-2xl shadow-2xl p-4 z-20 backdrop-blur-xl animate-in fade-in zoom-in-95 duration-150"
        >
          <div className="flex items-center justify-between pb-2 border-b border-slate-800 mb-3">
            <div className="flex items-center gap-2">
              <span className="w-2 h-2 rounded-full bg-indigo-400 animate-pulse" />
              <span className="text-xs font-bold text-white">لوحة Todd المصغرة</span>
            </div>
            <span className="text-[10px] bg-slate-800 text-slate-300 px-2 py-0.5 rounded-full font-mono">
              {activeProjectName}
            </span>
          </div>

          <div className="flex items-center gap-2 text-[11px] text-indigo-300 bg-indigo-950/60 border border-indigo-800/40 rounded-lg p-2 mb-3">
            <Monitor className="w-3.5 h-3.5 shrink-0" />
            <span>سياق الشاشة الدلالي متاح ومحمي</span>
          </div>

          <div className="grid grid-cols-2 gap-2 mb-3">
            <button
              onClick={onTogglePause}
              className={`flex items-center justify-center gap-1.5 py-1.5 rounded-lg text-xs font-medium border transition ${
                isPaused
                  ? 'bg-emerald-600/20 border-emerald-500 text-emerald-300'
                  : 'bg-amber-600/20 border-amber-500 text-amber-300'
              }`}
            >
              {isPaused ? <Play className="w-3.5 h-3.5" /> : <Pause className="w-3.5 h-3.5" />}
              <span>{isPaused ? 'استئناف' : 'إيقاف مؤقت'}</span>
            </button>
            <button
              onClick={onOpenMainApp}
              className="flex items-center justify-center gap-1.5 py-1.5 bg-slate-800 hover:bg-slate-700 border border-slate-700 rounded-lg text-xs font-medium text-slate-200 transition"
            >
              <span>فتح التطبيق</span>
              <ArrowUpRight className="w-3.5 h-3.5" />
            </button>
          </div>

          <div className="relative">
            <input
              type="text"
              placeholder="اطلب من Todd شيئاً سريعاً..."
              className="w-full bg-slate-950 border border-slate-800 rounded-xl px-3 py-2 text-xs text-white placeholder-slate-500 focus:outline-none focus:border-indigo-500"
            />
            <button className="absolute left-2 top-2 p-1 text-slate-400 hover:text-indigo-400">
              <Mic className="w-4 h-4" />
            </button>
          </div>
        </div>
      )}
    </div>
  );
};
