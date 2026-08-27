import { Minus, Plus, RotateCcw, X } from "lucide-react";
import {
  type PointerEvent as ReactPointerEvent,
  useCallback,
  useEffect,
  useRef,
  useState,
} from "react";
import type { Translation } from "../i18n/translations";

interface ImageViewerProps {
  src: string;
  translation: Translation;
  onClose: () => void;
}

interface Point {
  x: number;
  y: number;
}

const MIN_ZOOM = 1;
const MAX_ZOOM = 8;

export function ImageViewer({ src, translation, onClose }: ImageViewerProps) {
  const viewportRef = useRef<HTMLDivElement | null>(null);
  const dragRef = useRef<{ pointerId: number; point: Point } | null>(null);
  const [zoom, setZoom] = useState(1);
  const [pan, setPan] = useState<Point>({ x: 0, y: 0 });

  useEffect(() => {
    const handleEscape = (event: KeyboardEvent) => {
      if (event.key !== "Escape") return;
      event.preventDefault();
      event.stopPropagation();
      onClose();
    };
    window.addEventListener("keydown", handleEscape, true);
    return () => window.removeEventListener("keydown", handleEscape, true);
  }, [onClose]);

  const reset = () => {
    setZoom(1);
    setPan({ x: 0, y: 0 });
  };
  const setZoomAround = useCallback(
    (nextValue: number, origin: Point = { x: 0, y: 0 }) => {
      const next = Math.min(MAX_ZOOM, Math.max(MIN_ZOOM, nextValue));
      if (next === zoom) return;
      const ratio = next / zoom;
      setPan((current) => ({
        x:
          finiteCoordinate(origin.x) -
          (finiteCoordinate(origin.x) - finiteCoordinate(current.x)) * ratio,
        y:
          finiteCoordinate(origin.y) -
          (finiteCoordinate(origin.y) - finiteCoordinate(current.y)) * ratio,
      }));
      setZoom(next);
    },
    [zoom],
  );

  useEffect(() => {
    const viewport = viewportRef.current;
    if (viewport === null) return;
    const handleWheel = (event: WheelEvent) => {
      event.preventDefault();
      const bounds = viewport.getBoundingClientRect();
      setZoomAround(zoom * Math.exp(-event.deltaY * 0.0015), {
        x:
          finiteCoordinate(event.clientX) -
          finiteCoordinate(bounds.left) -
          finiteCoordinate(bounds.width) / 2,
        y:
          finiteCoordinate(event.clientY) -
          finiteCoordinate(bounds.top) -
          finiteCoordinate(bounds.height) / 2,
      });
    };
    viewport.addEventListener("wheel", handleWheel, { passive: false });
    return () => viewport.removeEventListener("wheel", handleWheel);
  }, [setZoomAround, zoom]);
  const startDrag = (event: ReactPointerEvent<HTMLDivElement>) => {
    if (event.button !== 0) return;
    event.currentTarget.setPointerCapture(event.pointerId);
    dragRef.current = { pointerId: event.pointerId, point: pointerPoint(event) };
  };
  const drag = (event: ReactPointerEvent<HTMLDivElement>) => {
    const current = dragRef.current;
    if (current === null || current.pointerId !== event.pointerId) return;
    const next = pointerPoint(event);
    setPan((value) => ({
      x: value.x + next.x - current.point.x,
      y: value.y + next.y - current.point.y,
    }));
    dragRef.current = { pointerId: event.pointerId, point: next };
  };
  const stopDrag = (event: ReactPointerEvent<HTMLDivElement>) => {
    if (dragRef.current?.pointerId === event.pointerId) dragRef.current = null;
    if (event.currentTarget.hasPointerCapture(event.pointerId)) {
      event.currentTarget.releasePointerCapture(event.pointerId);
    }
  };

  return (
    <div className="image-viewer-backdrop">
      <section
        className="image-viewer"
        aria-modal="true"
        role="dialog"
        aria-label={translation.fullSizeImage}
      >
        <header className="image-viewer-toolbar">
          <span>{Math.round(zoom * 100)}%</span>
          <button
            type="button"
            aria-label={translation.zoomOut}
            disabled={zoom <= MIN_ZOOM}
            onClick={() => setZoomAround(zoom / 1.25)}
          >
            <Minus aria-hidden="true" size={19} />
          </button>
          <button
            type="button"
            aria-label={translation.zoomIn}
            disabled={zoom >= MAX_ZOOM}
            onClick={() => setZoomAround(zoom * 1.25)}
          >
            <Plus aria-hidden="true" size={19} />
          </button>
          <button type="button" aria-label={translation.resetImageView} onClick={reset}>
            <RotateCcw aria-hidden="true" size={18} />
          </button>
          <button type="button" aria-label={translation.closeImage} onClick={onClose}>
            <X aria-hidden="true" size={21} />
          </button>
        </header>
        <div
          ref={viewportRef}
          className="image-viewer-viewport"
          data-zoom={zoom}
          role="application"
          aria-label={translation.imageViewerHelp}
          onPointerDown={startDrag}
          onPointerMove={drag}
          onPointerUp={stopDrag}
          onPointerCancel={stopDrag}
          onDoubleClick={reset}
        >
          <img
            src={originalImageUrl(src)}
            alt=""
            draggable={false}
            style={{ transform: `translate(${pan.x}px, ${pan.y}px) scale(${zoom})` }}
          />
        </div>
        <p className="image-viewer-help">{translation.imageViewerHelp}</p>
      </section>
    </div>
  );
}

function originalImageUrl(value: string): string {
  try {
    const url = new URL(value);
    if (url.hostname === "pbs.twimg.com" && url.pathname.startsWith("/media/")) {
      url.searchParams.set("name", "orig");
      return url.toString();
    }
  } catch {
    return value;
  }
  return value;
}

function pointerPoint(event: ReactPointerEvent<HTMLDivElement>): Point {
  return {
    x: finiteCoordinate(event.clientX),
    y: finiteCoordinate(event.clientY),
  };
}

function finiteCoordinate(value: number): number {
  return Number.isFinite(value) ? value : 0;
}
