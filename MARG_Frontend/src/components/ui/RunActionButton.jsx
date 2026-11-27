import { AnimatePresence, motion } from "motion/react";
import { CheckCircle2, Loader2, Sparkles, Zap } from "lucide-react";

function AnimatedText({ text, className = "", delayStep = 0.012 }) {
  const chars = String(text || "").split("");
  return (
    <span className={`inline-flex ${className}`}>
      <AnimatePresence mode="popLayout" initial={false}>
        <motion.span key={text} className="inline-flex will-change-transform">
          {chars.map((char, i) => (
            <motion.span
              key={i}
              initial={{ y: 8, opacity: 0, scale: 0.7, filter: "blur(2px)" }}
              animate={{ y: 0, opacity: 1, scale: 1, filter: "blur(0px)" }}
              exit={{ y: -8, opacity: 0, scale: 0.7, filter: "blur(2px)" }}
              transition={{
                type: "spring",
                stiffness: 260,
                damping: 18,
                mass: 1,
                delay: i * delayStep,
              }}
              style={{ display: "inline-block", whiteSpace: char === " " ? "pre" : undefined }}
            >
              {char}
            </motion.span>
          ))}
        </motion.span>
      </AnimatePresence>
    </span>
  );
}

export function RunActionButton({
  idleLabel = "Run Action",
  runningLabel = "Processing…",
  doneLabel = "Completed",
  busy = false,
  done = false,
  disabled = false,
  onClick,
  type = "button",
  className = "",
  variant = "primary", // "primary" | "secondary"
}) {
  const isPrimary = variant === "primary";
  const label = busy ? runningLabel : done ? doneLabel : idleLabel;

  return (
    <motion.button
      type={type}
      disabled={disabled || busy}
      onClick={onClick}
      whileHover={!disabled && !busy ? { scale: 1.015 } : undefined}
      whileTap={!disabled && !busy ? { scale: 0.97 } : undefined}
      transition={{ type: "spring", stiffness: 320, damping: 22 }}
      className={`group relative inline-flex min-h-[44px] items-center justify-center gap-2.5 overflow-hidden rounded-xl px-5 py-2.5 text-sm font-semibold shadow-sm transition-colors disabled:cursor-not-allowed disabled:opacity-50 ${
        isPrimary
          ? "bg-[#1B4332] text-[#F7F5F0] hover:bg-[#24523E] border border-[#1B4332] shadow-[0_6px_18px_rgba(27,67,50,0.18)]"
          : "bg-[#FFFFFF] text-[#1B4332] hover:bg-[#EEF4EF] border border-[#B8D0BF]"
      } ${className}`}
    >
      <span className="relative z-10 flex items-center gap-2">
        {busy ? (
          <Loader2 className={`size-4 animate-spin ${isPrimary ? "text-emerald-200" : "text-[#2D6A4F]"}`} />
        ) : done ? (
          <CheckCircle2 className={`size-4 ${isPrimary ? "text-emerald-300" : "text-[#2D6A4F]"}`} />
        ) : isPrimary ? (
          <Zap className="size-4 text-amber-300 transition-transform duration-200 group-hover:scale-110" />
        ) : (
          <Sparkles className="size-4 text-[#2D6A4F] transition-transform duration-200 group-hover:rotate-12" />
        )}
        <AnimatedText text={label} />
      </span>
      <span
        aria-hidden="true"
        className="pointer-events-none absolute inset-0 -translate-x-full bg-gradient-to-r from-transparent via-white/15 to-transparent transition-transform duration-700 group-hover:translate-x-full"
      />
    </motion.button>
  );
}

export default RunActionButton;
