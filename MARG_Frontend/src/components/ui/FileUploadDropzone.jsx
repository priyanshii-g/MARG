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
    <div className={`space-y-2.5 ${className}`}>
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
            ? "border-[#2D6A4F] bg-[#E9F2EC] shadow-sm"
            : "border-[#C4D6C8] bg-[#FAF9F6] hover:border-[#2D6A4F] hover:bg-[#EEF4EF]"
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
        <div className="mx-auto mb-2 flex size-10 items-center justify-center rounded-full bg-[#E9F2EC] text-[#1B4332] border border-[#C4D6C8]">
          <CloudUpload className="size-5" />
        </div>
        <p className="text-sm font-semibold text-[#1B4332]">{title}</p>
        <p className="mt-1 text-xs text-[#59645C]">{description}</p>
      </motion.div>

      {files.length > 0 && (
        <div className="space-y-1.5">
          {files.map((file, idx) => {
            const isCode = /\.(kml|json|geojson)$/i.test(file.name);
            return (
              <motion.div
                key={`${file.name}-${idx}`}
                initial={{ opacity: 0, y: 6 }}
                animate={{ opacity: 1, y: 0 }}
                className="flex items-center justify-between gap-3 rounded-lg border border-[#D8E8DD] bg-[#FFFFFF] px-3.5 py-2.5 text-xs shadow-xs"
              >
                <div className="flex items-center gap-2.5 min-w-0">
                  {isCode ? (
                    <FileCode2 className="size-4 shrink-0 text-[#2D6A4F]" />
                  ) : (
                    <FileText className="size-4 shrink-0 text-[#B45309]" />
                  )}
                  <span className="truncate font-medium text-[#1C2420]">{file.name}</span>
                  <span className="shrink-0 font-mono text-[10px] text-[#68736B]">{formatBytes(file.size)}</span>
                </div>
                <div className="flex items-center gap-2 shrink-0">
                  <CheckCircle2 className="size-3.5 text-[#2D6A4F]" />
                  {onFileRemove && (
                    <button
                      type="button"
                      onClick={(e) => {
                        e.stopPropagation();
                        onFileRemove(idx);
                      }}
                      className="rounded border-0 bg-transparent p-1 text-[#68736B] hover:bg-rose-50 hover:text-rose-700 transition-colors"
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
