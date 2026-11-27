import { useRef, useState } from "react";
import { motion } from "motion/react";
import { CloudUpload, FileCode2, FileText, Trash2, CheckCircle2 } from "lucide-react";

function formatBytes(bytes) {
  if (!bytes) return "0 B";
  const k = 1024;
  const sizes = ["B", "KB", "MB", "GB"];
  const i = Math.floor(Math.log(bytes) / Math.log(k));
  return `${parseFloat((bytes / Math.pow(k, i)).toFixed(1))} ${sizes[i]}`;
}

export function FileUploadDropzone({
  title = "Upload Project Files",
  description = "Drag & drop files here or click to browse",
  accept = ".pdf",
  multiple = true,
  files = [],
  onFilesSelect,
  onFileRemove,
  className = "",
}) {
  const inputRef = useRef(null);
  const [dragging, setDragging] = useState(false);

  function handleSelect(fileList) {
    const arr = Array.from(fileList || []);
    if (arr.length && onFilesSelect) {
      onFilesSelect(arr);
    }
  }

  return (
    <div className={`space-y-3 ${className}`}>
      <motion.div
        whileHover={{ scale: 1.005 }}
        onDragOver={(e) => {
          e.preventDefault();
          setDragging(true);
        }}
        onDragLeave={() => setDragging(false)}
        onDrop={(e) => {
          e.preventDefault();
          setDragging(false);
          handleSelect(e.dataTransfer?.files);
        }}
        onClick={() => inputRef.current?.click()}
        className={`cursor-pointer rounded-xl border-2 border-dashed p-5 text-center transition-all ${
          dragging
            ? "border-emerald-400 bg-emerald-950/50 shadow-[0_0_24px_rgba(16,185,129,0.18)]"
            : "border-emerald-800/70 bg-[#0b1510] hover:border-emerald-500/70 hover:bg-[#101e17]"
        }`}
      >
        <input
          ref={inputRef}
          type="file"
          accept={accept}
          multiple={multiple}
          onChange={(e) => {
            handleSelect(e.target.files);
            e.target.value = "";
          }}
          className="hidden"
        />
        <div className="mx-auto mb-2.5 flex size-10 items-center justify-center rounded-full bg-emerald-900/50 text-emerald-300 border border-emerald-700/50">
          <CloudUpload className="size-5" />
        </div>
        <p className="text-sm font-semibold text-white">{title}</p>
        <p className="mt-1 text-xs text-slate-400">{description}</p>
      </motion.div>

      {files.length > 0 && (
        <div className="space-y-2">
          {files.map((file, idx) => {
            const isCode = /\.(kml|json|geojson)$/i.test(file.name);
            return (
              <motion.div
                key={`${file.name}-${idx}`}
                initial={{ opacity: 0, y: 6 }}
                animate={{ opacity: 1, y: 0 }}
                className="flex items-center justify-between gap-3 rounded-lg border border-emerald-800/60 bg-[#101c16] px-3.5 py-2.5 text-xs"
              >
                <div className="flex items-center gap-2.5 min-w-0">
                  {isCode ? (
                    <FileCode2 className="size-4 shrink-0 text-emerald-400" />
                  ) : (
                    <FileText className="size-4 shrink-0 text-amber-400" />
                  )}
                  <span className="truncate font-medium text-slate-100">{file.name}</span>
                  <span className="shrink-0 font-mono text-[10px] text-slate-400">{formatBytes(file.size)}</span>
                </div>
                <div className="flex items-center gap-2 shrink-0">
                  <CheckCircle2 className="size-3.5 text-emerald-400" />
                  {onFileRemove && (
                    <button
                      type="button"
                      onClick={(e) => {
                        e.stopPropagation();
                        onFileRemove(idx);
                      }}
                      className="rounded p-1 text-slate-400 hover:bg-rose-950/60 hover:text-rose-300 transition-colors"
                      aria-label={`Remove ${file.name}`}
                    >
                      <Trash2 className="size-3.5" />
                    </button>
                  )}
                </div>
              </motion.div>
            );
          })}
        </div>
      )}
    </div>
  );
}

export default FileUploadDropzone;
