export function ShimmerButton({ children, className = "", disabled = false, ...props }) {
  return (
    <button
      disabled={disabled}
      className={`group relative inline-flex items-center justify-center gap-2 overflow-hidden rounded-full bg-[#143627] px-6 py-2.5 text-sm font-semibold text-[#FFFFFF] shadow-[0_2px_8px_rgba(45,106,79,0.18)] transition-all duration-300 hover:bg-[#1C4A36] hover:shadow-[0_4px_14px_rgba(45,106,79,0.22)] active:scale-[0.98] disabled:cursor-not-allowed disabled:opacity-50 border-0 ${className}`}
      {...props}
    >
      <span className="relative z-10 flex items-center gap-2">{children}</span>
      <span
        aria-hidden="true"
        className="pointer-events-none absolute inset-0 -translate-x-full bg-gradient-to-r from-transparent via-white/20 to-transparent transition-transform duration-700 group-hover:translate-x-full"
      />
    </button>
  );
}

export default ShimmerButton;
