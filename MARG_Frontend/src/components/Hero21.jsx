import { ArrowRight, ChevronDown, Globe2 } from "lucide-react";
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
      <rect width="36" height="36" rx="6" fill="currentColor" fillOpacity="0.14" />
      <path
        d="M9 26C12.5 21 15.5 14 18 10C20.5 14 23.5 21 27 26"
        stroke="currentColor"
        strokeWidth="2.4"
        strokeLinecap="round"
        strokeLinejoin="round"
      />
      <path
        d="M14 26L18 18.5L22 26"
        stroke="currentColor"
        strokeWidth="2"
        strokeLinecap="round"
        strokeLinejoin="round"
        strokeOpacity="0.7"
      />
    </svg>
  );
}

const sectionVariants = {
  hidden: {},
  visible: {
    transition: { staggerChildren: 0.13, delayChildren: 0.08 },
  },
};

const navWrapVariants = {
  hidden: {},
  visible: { transition: { staggerChildren: 0.07 } },
};

const navItemVariants = {
  hidden: { opacity: 0, y: -16, filter: "blur(6px)" },
  visible: {
    opacity: 1,
    y: 0,
    filter: "blur(0px)",
    transition: { type: "spring", duration: 0.55, bounce: 0.2 },
  },
};

const bgVariants = {
  hidden: { opacity: 0, scale: 1.1, filter: "blur(24px)" },
  visible: {
    opacity: 1,
    scale: 1,
    filter: "blur(0px)",
    transition: { duration: 1.6, ease: [0.16, 1, 0.3, 1] },
  },
};

const glowVariants = {
  hidden: { opacity: 0, scale: 0.4 },
  visible: {
    opacity: [0, 0.55, 0.35],
    scale: [0.4, 1.15, 1],
    transition: { duration: 1.8, ease: "easeOut", times: [0, 0.6, 1] },
  },
};

const descVariants = {
  hidden: { opacity: 0, y: 22, filter: "blur(10px)" },
  visible: {
    opacity: 1,
    y: 0,
    filter: "blur(0px)",
    transition: { type: "spring", duration: 0.8, bounce: 0 },
  },
};

const ctaVariants = {
  hidden: { opacity: 0, scale: 0.82, filter: "blur(8px)" },
  visible: {
    opacity: 1,
    scale: 1,
    filter: "blur(0px)",
    transition: { type: "spring", duration: 0.7, bounce: 0.3 },
  },
};

const cardVariants = {
  hidden: { opacity: 0, x: -40, rotate: -6, filter: "blur(14px)" },
  visible: {
    opacity: 1,
    x: 0,
    rotate: 0,
    filter: "blur(0px)",
    transition: { type: "spring", duration: 1.1, bounce: 0.22 },
  },
};

const trustVariants = {
  hidden: { opacity: 0, rotateX: 90, y: 20 },
  visible: {
    opacity: 1,
    rotateX: 0,
    y: 0,
    transition: { type: "spring", duration: 0.8, bounce: 0.15, delay: 0.2 },
  },
};

const charContainerVariants = {
  hidden: {},
  visible: { transition: { staggerChildren: 0.03, delayChildren: 0 } },
};

const charVariants = {
  hidden: { opacity: 0, y: 40, rotateZ: -8, filter: "blur(8px)" },
  visible: {
    opacity: 1,
    y: 0,
    rotateZ: 0,
    filter: "blur(0px)",
    transition: { type: "spring", duration: 0.55, bounce: 0.25 },
  },
};

function DropCharsLine({ text, className = "" }) {
  return (
    <motion.span
      className={`block overflow-visible ${className}`}
      variants={charContainerVariants}
    >
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

function FloatingCard({ previewImage, previewLabel, onClick, variants }) {
  return (
    <motion.button
      type="button"
      onClick={onClick}
      variants={variants}
      animate={{ y: [0, -10, 0] }}
      transition={{ duration: 4, ease: "easeInOut", repeat: Infinity, repeatType: "loop" }}
      className="group absolute bottom-8 left-7 z-20 w-[230px] cursor-pointer rounded-md bg-emerald-950/25 p-1.5 text-left shadow-[0_20px_50px_rgba(6,78,59,0.22),inset_0_0_0_1px_rgba(255,255,255,0.18)] backdrop-blur-md transition-[background-color,box-shadow] duration-300 ease-out hover:bg-emerald-950/35 hover:shadow-[0_28px_60px_rgba(6,78,59,0.32),inset_0_0_0_1px_rgba(255,255,255,0.26)] active:scale-[0.96] sm:left-11 lg:left-14"
    >
      <img
        src={previewImage}
        alt=""
        className="h-[6.2rem] w-full rounded-sm object-cover object-center shadow-[0_0_0_1px_rgba(255,255,255,0.1)]"
      />
      <span className="mt-2 flex min-h-7 items-center justify-between gap-3 px-1 text-sm font-normal text-white">
        {previewLabel}
        <ArrowRight className="size-4 -rotate-45 transition-transform duration-200 ease-out group-hover:translate-x-0.5 group-hover:-translate-y-0.5" />
      </span>
    </motion.button>
  );
}

export default function Hero21({
  brandName = "MARG",
  headingLine1 = "Plan Safer Roads.",
  headingLine2 = "Protect Nature.",
  description = "MARG turns multi-layer GIS data, DPR documents, and statutory Indian environmental law into clear, evidence-backed highway intelligence.",
  primaryCtaLabel = "Explore Workflows",
  primaryCtaHref = "#modes",
  languageLabel = "IN · EN",
  actionLabel = "Launch Project Review",
  previewLabel = "MARG Mode 2 Studio",
  trustLabelLine1 = "Built for MoEFCC, NHAI",
  trustLabelLine2 = "& Public Appraisal",
  backgroundImage = "https://assets.watermelon.sh/hero-20-bg.avif",
  previewImage = "https://assets.watermelon.sh/hero-20-bg.avif",
  onMode1,
  onMode2,
  onEia,
}) {
  const navLinks = [
    { label: "Route Planner", onClick: onMode1 },
    { label: "Project Review", onClick: onMode2 },
    { label: "EIA Reports", onClick: onEia },
    { label: "Workflows", href: "#modes" },
  ];

  return (
    <section className="relative isolate min-h-screen overflow-hidden bg-stone-100 font-sans text-emerald-950 antialiased">
      <motion.div
        className="relative flex min-h-screen w-full flex-col overflow-hidden px-7 py-4 sm:px-11 lg:px-14"
        initial="hidden"
        whileInView="visible"
        viewport={{ once: true, amount: 0.25 }}
        variants={sectionVariants}
      >
        <motion.img
          variants={bgVariants}
          src={backgroundImage}
          alt=""
          className="pointer-events-none absolute inset-0 h-full w-full object-cover"
        />

        <motion.div
          variants={glowVariants}
          aria-hidden="true"
          className="pointer-events-none absolute left-1/2 top-1/2 z-0 h-[70vmin] w-[70vmin] -translate-x-1/2 -translate-y-1/2 rounded-full bg-emerald-400/30 blur-[80px]"
        />

        <motion.nav
          variants={navWrapVariants}
          className="relative z-20 flex min-h-10 w-full items-center justify-between"
        >
          <motion.a
            variants={navItemVariants}
            href="#modes"
            className="inline-flex min-h-10 items-center gap-2.5 text-lg leading-none font-semibold tracking-[-0.035em] text-emerald-950 transition-[opacity,transform] duration-200 ease-out hover:opacity-80 active:scale-[0.96]"
          >
            <LogoIcon className="size-8 text-emerald-900" />
            <span>{brandName}</span>
          </motion.a>

          <div className="hidden items-center gap-[3.1rem] lg:flex">
            {navLinks.map((link) =>
              link.onClick ? (
                <motion.button
                  key={link.label}
                  type="button"
                  variants={navItemVariants}
                  onClick={link.onClick}
                  className="inline-flex min-h-10 items-center text-sm font-medium text-emerald-950/90 transition-[opacity,transform] duration-200 ease-out hover:opacity-75 active:scale-[0.96]"
                >
                  {link.label}
                </motion.button>
              ) : (
                <motion.a
                  key={link.label}
                  variants={navItemVariants}
                  href={link.href}
                  className="inline-flex min-h-10 items-center text-sm font-medium text-emerald-950/90 transition-[opacity,transform] duration-200 ease-out hover:opacity-75 active:scale-[0.96]"
                >
                  {link.label}
                </motion.a>
              )
            )}
          </div>

          <div className="flex items-center gap-4">
            <motion.button
              variants={navItemVariants}
              type="button"
              className="hidden min-h-10 items-center gap-1.5 text-sm font-medium text-emerald-950 transition-[opacity,transform] duration-200 ease-out hover:opacity-90 active:scale-[0.96] sm:inline-flex"
            >
              <Globe2 className="size-4" aria-hidden="true" />
              {languageLabel}
              <ChevronDown className="size-4" aria-hidden="true" />
            </motion.button>
            <motion.button
              variants={navItemVariants}
              type="button"
              onClick={onMode2}
              className="group inline-flex min-h-10 items-center justify-center gap-2 rounded-sm bg-emerald-900 px-5 text-sm font-medium text-amber-50 shadow-[inset_0_1px_4px_2px_rgba(255,255,255,0.2),inset_0_-1px_4px_2px_rgba(0,0,0,0.2)] transition-[background-color,transform,box-shadow] duration-200 ease-out hover:bg-emerald-950 active:scale-[0.96]"
            >
              {actionLabel}
              <ArrowRight className="size-4 transition-transform duration-200 ease-out group-hover:translate-x-0.5" />
            </motion.button>
          </div>
        </motion.nav>

        <div className="relative z-10 flex flex-1 flex-col justify-center pt-16 pb-32 sm:pt-20 md:pb-36 lg:pt-10">
          <div className="max-w-5xl">
            <h1 className="max-w-5xl text-[clamp(3.25rem,5.4vw,5.15rem)] leading-[0.94] font-normal tracking-[-0.065em] text-emerald-900">
              <DropCharsLine text={headingLine1} />
              <DropCharsLine text={headingLine2} />
            </h1>

            <motion.p
              variants={descVariants}
              className="mt-5 max-w-lg text-sm leading-[1.35] font-medium text-pretty text-emerald-950"
            >
              {description}
            </motion.p>

            <motion.a
              variants={ctaVariants}
              href={primaryCtaHref}
              className="group mt-5 inline-flex min-h-10 items-center border-b border-emerald-950 pb-1 text-base font-medium tracking-[-0.035em] text-emerald-950 transition-[opacity,transform] duration-200 ease-out hover:opacity-75 active:scale-[0.96]"
            >
              {primaryCtaLabel}
              <ArrowRight className="ml-1 size-4 -rotate-45 transition-transform duration-200 ease-out group-hover:translate-x-0.5 group-hover:-translate-y-0.5" />
            </motion.a>
          </div>
        </div>

        <FloatingCard
          previewImage={previewImage}
          previewLabel={previewLabel}
          onClick={onMode2}
          variants={cardVariants}
        />

        <motion.p
          variants={trustVariants}
          style={{ perspective: 600 }}
          className="absolute right-7 bottom-9 z-20 hidden max-w-[12rem] text-left text-sm leading-snug font-normal text-white drop-shadow-[0_2px_12px_rgba(6,78,59,0.3)] sm:block sm:right-11 md:text-right lg:right-14"
        >
          {trustLabelLine1}
          <br />
          {trustLabelLine2}
        </motion.p>
      </motion.div>
    </section>
  );
}
