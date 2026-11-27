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
      className={`group relative inline-flex min-h-[46px] items-center justify-center gap-2.5 overflow-hidden rounded-full px-6 py-2.5 text-sm font-semibold transition-all disabled:cursor-not-allowed disabled:opacity-50 ${
        isPrimary
          ? "bg-[#24523E] text-[#FFFFFF] hover:bg-[#2E664D] border border-[#24523E] shadow-[0_2px_8px_rgba(45,106,79,0.18)]"
          : "bg-[#E4EFE7] text-[#24523E] hover:bg-[#D5E6DA] border border-[#CBE0D1] shadow-none"
      } ${className}`}
    >
      <span className="relative z-10 flex items-center gap-2">
        {busy ? (
          <Loader2 className={`size-4 animate-spin ${isPrimary ? "text-emerald-200" : "text-[#24523E]"}`} />
        ) : done ? (
          <CheckCircle2 className={`size-4 ${isPrimary ? "text-emerald-300" : "text-[#24523E]"}`} />
        ) : isPrimary ? (
          <Zap className="size-4 text-amber-300 transition-transform duration-200 group-hover:scale-110" />
        ) : (
          <Sparkles className="size-4 text-[#24523E] transition-transform duration-200 group-hover:rotate-12" />
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
