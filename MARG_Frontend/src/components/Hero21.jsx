import { ArrowRight } from "lucide-react";
import { motion } from "motion/react";

function LogoIcon({ className = "size-8" }) {
  return (
    <svg
      viewBox="0 0 36 36"
      fill="none"
      xmlns="http://www.w3.org/2000/svg"
      className={className}
      aria-hidden="true"
    >
      <rect width="36" height="36" rx="8" fill="#1B4332" />
      <path
        d="M9 26C12.5 21 15.5 14 18 10C20.5 14 23.5 21 27 26"
        stroke="#F7F5F0"
        strokeWidth="2.4"
        strokeLinecap="round"
        strokeLinejoin="round"
      />
      <path
        d="M14 26L18 18.5L22 26"
        stroke="#A7F3D0"
        strokeWidth="2"
        strokeLinecap="round"
        strokeLinejoin="round"
      />
    </svg>
  );
}

const sectionVariants = {
  hidden: {},
  visible: {
    transition: { staggerChildren: 0.1, delayChildren: 0.05 },
  },
};

const navWrapVariants = {
  hidden: {},
  visible: { transition: { staggerChildren: 0.06 } },
};

const navItemVariants = {
  hidden: { opacity: 0, y: -12, filter: "blur(4px)" },
  visible: {
    opacity: 1,
    y: 0,
    filter: "blur(0px)",
    transition: { type: "spring", duration: 0.5, bounce: 0.2 },
  },
};

const bgVariants = {
  hidden: { opacity: 0, scale: 1.06, filter: "blur(16px)" },
  visible: {
    opacity: 1,
    scale: 1,
    filter: "blur(0px)",
    transition: { duration: 1.4, ease: [0.16, 1, 0.3, 1] },
  },
};

const descVariants = {
  hidden: { opacity: 0, y: 18, filter: "blur(8px)" },
  visible: {
    opacity: 1,
    y: 0,
    filter: "blur(0px)",
    transition: { type: "spring", duration: 0.75, bounce: 0 },
  },
};

const ctaVariants = {
  hidden: { opacity: 0, scale: 0.9, filter: "blur(6px)" },
  visible: {
    opacity: 1,
    scale: 1,
    filter: "blur(0px)",
    transition: { type: "spring", duration: 0.65, bounce: 0.25 },
  },
};

const cardVariants = {
  hidden: { opacity: 0, x: -30, filter: "blur(10px)" },
  visible: {
    opacity: 1,
    x: 0,
    filter: "blur(0px)",
    transition: { type: "spring", duration: 0.9, bounce: 0.2 },
  },
};

const charContainerVariants = {
  hidden: {},
  visible: { transition: { staggerChildren: 0.025, delayChildren: 0 } },
};

const charVariants = {
  hidden: { opacity: 0, y: 30, rotateZ: -5, filter: "blur(6px)" },
  visible: {
    opacity: 1,
    y: 0,
    rotateZ: 0,
    filter: "blur(0px)",
    transition: { type: "spring", duration: 0.5, bounce: 0.22 },
  },
};

function DropCharsLine({ text, className = "" }) {
  return (
    <motion.span className={`block overflow-visible ${className}`} variants={charContainerVariants}>
      {text.split("").map((ch, i) => (
        <motion.span
          key={i}
          className="inline-block will-change-transform"
          variants={charVariants}
          style={{ whiteSpace: ch === " " ? "pre" : undefined }}
        >
          {ch === " " ? "\u00a0" : ch}
        </motion.span>
      ))}
    </motion.span>
  );
}

export default function Hero21({
  brandName = "MARG",
  headingLine1 = "Better Highways.",
  headingLine2 = "Balanced by Design.",
  description = "Plan corridors that weigh engineering terrain, flood resilience, local communities, and environmental law—all on one spatial canvas.",
  backgroundImage = "https://assets.watermelon.sh/hero-20-bg.avif",
  onMode1,
  onMode2,
  onEia,
}) {
  const navButtons = [
    { label: "Route Planner", onClick: onMode1 },
    { label: "Project Review", onClick: onMode2 },
    { label: "EIA Reports", onClick: onEia },
  ];

  return (
    <section className="relative isolate min-h-[76vh] overflow-hidden bg-[#F7F5F0] font-sans text-[#1B4332] antialiased">
      <motion.div
        className="relative flex min-h-[76vh] w-full flex-col overflow-hidden px-6 pt-5 pb-36 sm:px-10 lg:px-14"
        initial="hidden"
        whileInView="visible"
        viewport={{ once: true, amount: 0.2 }}
        variants={sectionVariants}
      >
        <motion.img
          variants={bgVariants}
          src={backgroundImage}
          alt=""
          className="pointer-events-none absolute inset-0 h-full w-full object-cover object-center opacity-90"
        />

        {/* Seamless creamish-beige blend at bottom so dashboard shares one unified background */}
        <div
          aria-hidden="true"
          className="pointer-events-none absolute inset-x-0 bottom-0 h-56 bg-gradient-to-b from-transparent via-[#F7F5F0]/75 to-[#F7F5F0]"
        />

        {/* Clean Top Navigation Bar — strictly the 3 core workflow buttons */}
        <motion.nav
          variants={navWrapVariants}
          className="relative z-20 mx-auto flex w-full max-w-6xl items-center justify-between"
        >
          <motion.a
            variants={navItemVariants}
            href="#modes"
            className="inline-flex items-center gap-2.5 text-lg font-bold tracking-[-0.03em] text-[#1B4332] no-underline transition-opacity hover:opacity-80"
          >
            <LogoIcon className="size-9" />
            <span>{brandName}</span>
          </motion.a>

          <div className="flex flex-wrap items-center gap-2 sm:gap-3">
            {navButtons.map((btn, idx) => (
              <motion.button
                key={btn.label}
                type="button"
                variants={navItemVariants}
                onClick={btn.onClick}
                className={`cursor-pointer rounded-full px-5 py-2.5 text-xs sm:text-sm font-semibold transition-all duration-200 border ${
                  idx === 1
                    ? "bg-[#143627] text-[#F7F5F0] border-[#143627] shadow-sm hover:bg-[#1E4D38]"
                    : "bg-[#2D6A4F] text-[#F7F5F0] border-[#2D6A4F] shadow-sm hover:bg-[#1D4635]"
                }`}
              >
                {btn.label}
              </motion.button>
            ))}
          </div>
        </motion.nav>

        <div className="relative z-10 mx-auto flex w-full max-w-6xl flex-1 flex-col justify-center pt-12 pb-16 sm:pt-14">
          <div className="max-w-3xl">
            <h1 className="text-[clamp(2.9rem,5vw,4.75rem)] leading-[0.96] font-semibold tracking-[-0.055em] text-[#1B4332]">
              <DropCharsLine text={headingLine1} />
              <DropCharsLine text={headingLine2} />
            </h1>

            <motion.p
              variants={descVariants}
              className="mt-5 max-w-xl text-sm sm:text-base leading-relaxed font-medium text-[#1C2420]/85"
            >
              {description}
            </motion.p>

            <div className="mt-7 flex flex-wrap items-center gap-5">
              <motion.a
                variants={ctaVariants}
                href="#modes"
                className="group inline-flex items-center gap-2 rounded-full bg-[#143627] px-7 py-3 text-sm font-semibold text-[#F7F5F0] no-underline shadow-md transition-all hover:bg-[#1C4A36] hover:shadow-lg"
              >
                Explore Workflows
                <ArrowRight className="size-4 transition-transform duration-200 group-hover:translate-x-0.5" />
              </motion.a>
              <motion.a
                variants={ctaVariants}
                href="#about"
                className="group inline-flex items-center gap-1.5 rounded-full border border-[#143627]/25 bg-[#FFFFFF]/70 px-5 py-2.5 text-sm font-semibold text-[#143627] no-underline backdrop-blur-sm transition-all hover:bg-[#FFFFFF]"
              >
                About MARG
                <ArrowRight className="size-3.5 -rotate-45 transition-transform group-hover:translate-x-0.5 group-hover:-translate-y-0.5" />
              </motion.a>
            </div>
          </div>
        </div>

        <motion.div
          variants={cardVariants}
          className="relative z-10 mx-auto hidden w-full max-w-6xl items-center justify-between text-xs font-medium text-[#1B4332]/75 sm:flex"
        >
          <span>13 Spatial Layers · Engineering, Social &amp; Environmental Intelligence</span>
          <span>Decision Support for Sustainable Infrastructure</span>
        </motion.div>
      </motion.div>
    </section>
  );
}
