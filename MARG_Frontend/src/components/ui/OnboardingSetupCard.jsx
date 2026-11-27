import { useEffect, useRef, useState } from "react";
import { motion } from "motion/react";
import { Check, CheckCircle2, ChevronDown } from "lucide-react";

const spring = {
  type: "spring",
  stiffness: 320,
  damping: 30,
  mass: 0.7,
};

export function OnboardingSetupCard({
  title = "Configure EIA Appraisal Profile",
  subtitle = "Configure statutory framework, corridor scale, and authoring perspective to support balanced, sustainable infrastructure appraisal.",
  focusOptions = [
    { id: "moefcc-cat-a", label: "MoEFCC Category A (NHAI)" },
    { id: "fca-forest", label: "FCA Forest Diversion" },
    { id: "nbwl-wildlife", label: "SC-NBWL Wildlife Corridor" },
  ],
  selectedFocus,
  onFocusChange,
  scaleOptions = ["< 25 km (Section)", "25 – 100 km (Package)", "100+ km (Interstate)"],
  selectedScale,
  onScaleChange,
  role = "",
  onRoleChange,
  step = 1,
  totalSteps = 3,
  onContinue,
  continueLabel = "Apply Appraisal Profile",
  imageUrl = "https://assets.watermelon.sh/hero-20-bg.avif",
}) {
  const [dropdownOpen, setDropdownOpen] = useState(false);
  const dropdownRef = useRef(null);

  useEffect(() => {
    function handleOutside(e) {
      if (dropdownRef.current && !dropdownRef.current.contains(e.target)) {
        setDropdownOpen(false);
      }
    }
    document.addEventListener("mousedown", handleOutside);
    return () => document.removeEventListener("mousedown", handleOutside);
  }, []);

  return (
    <motion.div
      initial={{ opacity: 0, y: 16 }}
      animate={{ opacity: 1, y: 0 }}
      transition={spring}
      className="overflow-hidden rounded-2xl border border-[#D8E8DD] bg-[#FFFFFF] shadow-sm"
    >
      <div className="grid md:grid-cols-[1.35fr_0.65fr]">
        <div className="p-6 sm:p-7 space-y-5">
          <div className="flex items-center justify-between gap-2">
            <span className="rounded-full bg-[#EEF4EF] px-3 py-1 font-mono text-[10px] font-bold uppercase tracking-wider text-[#1B4332] border border-[#D8E8DD]">
              Step {step} of {totalSteps} · EIA Setup
            </span>
          </div>

          <div>
            <h3 className="text-xl font-semibold tracking-tight text-[#1B4332]">{title}</h3>
            <p className="mt-1 text-xs leading-relaxed text-[#59645C]">{subtitle}</p>
          </div>

          <div>
            <label className="block text-[11px] font-semibold uppercase tracking-wider text-[#2D6A4F] mb-2">
              Primary Regulatory Focus
            </label>
            <div className="flex flex-wrap gap-2.5">
              {focusOptions.map((opt) => {
                const active = selectedFocus === opt.id;
                return (
                  <button
                    key={opt.id}
                    type="button"
                    onClick={() => onFocusChange?.(opt.id)}
                    className={`inline-flex items-center gap-1.5 rounded-lg px-3.5 py-2 text-xs font-semibold transition-all border ${
                      active
                        ? "bg-[#1B4332] text-[#F7F5F0] border-[#1B4332] shadow-sm"
                        : "bg-[#FAF9F6] text-[#1C2420] border-[#D9DDD3] hover:border-[#2D6A4F]"
                    }`}
                  >
                    {active && <CheckCircle2 className="size-3.5 text-emerald-300" />}
                    {opt.label}
                  </button>
                );
              })}
            </div>
          </div>

          <div className="grid gap-4 sm:grid-cols-2">
            <div ref={dropdownRef} className="relative">
              <label className="block text-[11px] font-semibold uppercase tracking-wider text-[#2D6A4F] mb-1.5">
                Corridor Package Scale
              </label>
              <button
                type="button"
                onClick={() => setDropdownOpen((v) => !v)}
                className="flex w-full items-center justify-between rounded-lg border border-[#CBD2C8] bg-[#FAF9F6] px-3.5 py-2.5 text-xs font-medium text-[#1C2420] hover:border-[#2D6A4F]"
              >
                <span>{selectedScale || scaleOptions[0]}</span>
                <ChevronDown className={`size-4 text-[#59645C] transition-transform ${dropdownOpen ? "rotate-180" : ""}`} />
              </button>
              {dropdownOpen && (
                <div className="absolute left-0 right-0 top-full z-30 mt-1 rounded-lg border border-[#D8E8DD] bg-[#FFFFFF] py-1 shadow-lg">
                  {scaleOptions.map((opt) => (
                    <button
                      key={opt}
                      type="button"
                      onClick={() => {
                        onScaleChange?.(opt);
                        setDropdownOpen(false);
                      }}
                      className="flex w-full items-center justify-between border-0 bg-transparent px-3.5 py-2 text-left text-xs text-[#1C2420] hover:bg-[#EEF4EF]"
                    >
                      <span>{opt}</span>
                      {selectedScale === opt && <Check className="size-3.5 text-[#1B4332]" />}
                    </button>
                  ))}
                </div>
              )}
            </div>

            <div>
              <label className="block text-[11px] font-semibold uppercase tracking-wider text-[#2D6A4F] mb-1.5">
                Preparing Agency / Role
              </label>
              <input
                type="text"
                value={role}
                onChange={(e) => onRoleChange?.(e.target.value)}
                placeholder="e.g., NHAI PIU / NABET EIA Consultant"
                className="w-full rounded-lg border border-[#CBD2C8] bg-[#FAF9F6] px-3.5 py-2.5 text-xs text-[#1C2420] placeholder:text-[#8A968D] focus:border-[#1B4332] focus:outline-none"
              />
            </div>
          </div>

          {onContinue && (
            <div className="pt-1">
              <button
                type="button"
                onClick={onContinue}
                className="rounded-lg bg-[#1B4332] px-5 py-2.5 text-xs font-semibold text-[#F7F5F0] hover:bg-[#24523E] transition-colors shadow-sm border-0"
              >
                {continueLabel}
              </button>
            </div>
          )}
        </div>

        <div className="relative hidden md:block min-h-[240px] border-l border-[#D8E8DD]">
          <img src={imageUrl} alt="Botanical corridor preview" className="h-full w-full object-cover" />
          <div className="absolute inset-0 bg-gradient-to-t from-[#1B4332]/90 via-[#1B4332]/30 to-transparent p-5 flex flex-col justify-end">
            <span className="font-mono text-[10px] font-bold uppercase tracking-wider text-emerald-200">
              Evidence-First Drafting
            </span>
            <p className="mt-1 text-xs leading-relaxed text-[#F7F5F0]">
              Every factual claim is bound to deterministic GIS layers, page-indexed DPR excerpts, and MoEFCC standards.
            </p>
          </div>
        </div>
      </div>
    </motion.div>
  );
}

export default OnboardingSetupCard;
