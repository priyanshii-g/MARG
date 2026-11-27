export function ShimmerButton({ children, className = "", disabled = false, ...props }) {
  return (
    <button
      disabled={disabled}
      className={`group relative inline-flex items-center justify-center gap-2 overflow-hidden rounded-lg bg-[#1B4332] px-5 py-2.5 text-sm font-semibold text-amber-50 shadow-sm transition-all duration-300 hover:bg-[#24523E] hover:shadow-lg active:scale-[0.98] disabled:cursor-not-allowed disabled:opacity-50 ${className}`}
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
