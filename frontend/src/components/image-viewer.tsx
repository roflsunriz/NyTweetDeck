import { ChevronLeft, ChevronRight, Minus, Plus, RotateCcw, X } from "lucide-react";
import {
  type PointerEvent as ReactPointerEvent,
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
} from "react";
import type { Translation } from "../i18n/translations";
import { useMediaQuery } from "../model/use-media-query";
import { useOverlayRoute } from "../model/use-overlay-route";

interface ImageViewerProps {
  src: string;
  sources?: readonly string[];
  translation: Translation;
  onClose: () => void;
}

interface Point {
  x: number;
  y: number;
}

interface ActiveDrag {
  pointerId: number;
  point: Point;
  origin: Point;
}

const MIN_ZOOM = 0.1;
const MAX_ZOOM = 8;
const EDGE_SWITCH_TOLERANCE = 1;
const EDGE_SWITCH_SWIPE_FRACTION = 0.49;

export function ImageViewer({ src, sources, translation, onClose }: ImageViewerProps) {
  const compactPresentation = useMediaQuery("(max-width: 599px)");
  const close = useOverlayRoute("media", onClose);
  const viewerRef = useRef<HTMLElement | null>(null);
  const viewportRef = useRef<HTMLDivElement | null>(null);
  const dragRef = useRef<ActiveDrag | null>(null);
  const imageSources = useMemo(() => normalizeImageSources(src, sources), [src, sources]);
  const [imageIndex, setImageIndex] = useState(() => selectedImageIndex(src, imageSources));
  const [zoom, setZoom] = useState(1);
  const [pan, setPan] = useState<Point>({ x: 0, y: 0 });
  const [dragging, setDragging] = useState(false);

  const reset = useCallback(() => {
    setZoom(1);
    setPan({ x: 0, y: 0 });
  }, []);

  const stopActiveDrag = useCallback((target: HTMLElement, pointerId?: number) => {
    const active = dragRef.current;
    if (active === null || (pointerId !== undefined && active.pointerId !== pointerId)) return;
    dragRef.current = null;
    setDragging(false);
    if (target.hasPointerCapture(active.pointerId)) target.releasePointerCapture(active.pointerId);
  }, []);

  const navigate = useCallback(
    (offset: number) => {
      if (imageSources.length <= 1) return;
      setImageIndex((current) => (current + offset + imageSources.length) % imageSources.length);
      reset();
    },
    [imageSources.length, reset],
  );

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
    setImageIndex(selectedImageIndex(src, imageSources));
    reset();
  }, [imageSources, reset, src]);

  useEffect(() => {
    viewerRef.current?.focus();
  }, []);

  useEffect(() => {
    const handleKeyboard = (event: KeyboardEvent) => {
      if (event.ctrlKey || event.metaKey || event.altKey) return;
      if (event.key === "Escape") {
        event.preventDefault();
        event.stopPropagation();
        close();
      } else if (event.key === "ArrowLeft") {
        event.preventDefault();
        navigate(-1);
      } else if (event.key === "ArrowRight") {
        event.preventDefault();
        navigate(1);
      } else if (event.key === "+" || event.key === "=") {
        event.preventDefault();
        setZoomAround(zoom * 1.25);
      } else if (event.key === "-") {
        event.preventDefault();
        setZoomAround(zoom / 1.25);
      } else if (event.key === "0") {
        event.preventDefault();
        stopActiveDrag(viewportRef.current ?? viewerRef.current ?? document.body);
        reset();
      }
    };
    window.addEventListener("keydown", handleKeyboard, true);
    return () => window.removeEventListener("keydown", handleKeyboard, true);
  }, [close, navigate, reset, setZoomAround, stopActiveDrag, zoom]);

  useEffect(() => {
    const viewport = viewportRef.current;
    if (viewport === null) return;
    const handleWheel = (event: WheelEvent) => {
      event.preventDefault();
      stopActiveDrag(viewport);
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
  }, [setZoomAround, stopActiveDrag, zoom]);

  const startDrag = (event: ReactPointerEvent<HTMLDivElement>) => {
    if (event.button !== 0 || event.isPrimary === false || dragRef.current !== null) return;
    event.preventDefault();
    event.currentTarget.setPointerCapture(event.pointerId);
    const point = pointerPoint(event);
    dragRef.current = { pointerId: event.pointerId, point, origin: point };
    setDragging(true);
  };

  const drag = (event: ReactPointerEvent<HTMLDivElement>) => {
    const current = dragRef.current;
    if (current === null || current.pointerId !== event.pointerId) return;
    if (event.pointerType !== "touch" && (event.buttons & 1) === 0) {
      stopActiveDrag(event.currentTarget, event.pointerId);
      return;
    }
    const next = pointerPoint(event);
    const horizontalDelta = next.x - current.point.x;
    const totalHorizontalDelta = next.x - current.origin.x;
    const bounds = event.currentTarget.getBoundingClientRect();
    if (
      imageSources.length > 1 &&
      bounds.width > 0 &&
      totalHorizontalDelta < 0 &&
      (next.x <= bounds.left + EDGE_SWITCH_TOLERANCE ||
        totalHorizontalDelta <= -bounds.width * EDGE_SWITCH_SWIPE_FRACTION)
    ) {
      stopActiveDrag(event.currentTarget, event.pointerId);
      navigate(1);
      return;
    }
    if (
      imageSources.length > 1 &&
      bounds.width > 0 &&
      totalHorizontalDelta > 0 &&
      (next.x >= bounds.right - EDGE_SWITCH_TOLERANCE ||
        totalHorizontalDelta >= bounds.width * EDGE_SWITCH_SWIPE_FRACTION)
    ) {
      stopActiveDrag(event.currentTarget, event.pointerId);
      navigate(-1);
      return;
    }
    setPan((value) => ({
      x: value.x + horizontalDelta,
      y: value.y + next.y - current.point.y,
    }));
    dragRef.current = { ...current, point: next };
  };

  const finishDrag = (event: ReactPointerEvent<HTMLDivElement>) => {
    const current = dragRef.current;
    if (current === null || current.pointerId !== event.pointerId) return;
    const next = pointerPoint(event);
    const bounds = event.currentTarget.getBoundingClientRect();
    const navigateAtLeft =
      imageSources.length > 1 &&
      bounds.width > 0 &&
      next.x < current.origin.x &&
      next.x <= bounds.left + EDGE_SWITCH_TOLERANCE;
    const navigateAtRight =
      imageSources.length > 1 &&
      bounds.width > 0 &&
      next.x > current.origin.x &&
      next.x >= bounds.right - EDGE_SWITCH_TOLERANCE;
    stopActiveDrag(event.currentTarget, event.pointerId);
    if (navigateAtLeft) navigate(1);
    if (navigateAtRight) navigate(-1);
  };

  const cancelDrag = (event: ReactPointerEvent<HTMLDivElement>) => {
    stopActiveDrag(event.currentTarget, event.pointerId);
  };

  const resetFromPointer = (event: ReactPointerEvent<HTMLDivElement>) => {
    stopActiveDrag(event.currentTarget);
    reset();
  };

  const currentSource = imageSources[imageIndex] ?? src;
  const presentationProps = compactPresentation
    ? ({ role: "region" } as const)
    : ({ "aria-modal": "true", role: "dialog" } as const);

  return (
    <div className="image-viewer-backdrop">
      <section
        ref={viewerRef}
        className="image-viewer"
        {...presentationProps}
        aria-label={translation.fullSizeImage}
        data-presentation={compactPresentation ? "full-page" : "modal"}
        tabIndex={-1}
      >
        <header className="image-viewer-toolbar">
          <span>{Math.round(zoom * 100)}%</span>
          {imageSources.length > 1 && (
            <>
              <button
                type="button"
                aria-label={translation.previousImage}
                onClick={() => navigate(-1)}
              >
                <ChevronLeft aria-hidden="true" size={19} />
              </button>
              <span className="image-viewer-position" aria-live="polite">
                {imageIndex + 1} / {imageSources.length}
              </span>
              <button type="button" aria-label={translation.nextImage} onClick={() => navigate(1)}>
                <ChevronRight aria-hidden="true" size={19} />
              </button>
            </>
          )}
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
          <button type="button" aria-label={translation.closeImage} onClick={close}>
            <X aria-hidden="true" size={21} />
          </button>
        </header>
        <div
          ref={viewportRef}
          className="image-viewer-viewport"
          data-zoom={zoom}
          data-pan-x={pan.x}
          data-pan-y={pan.y}
          data-dragging={dragging}
          data-image-index={imageIndex}
          data-image-count={imageSources.length}
          role="application"
          aria-label={translation.imageViewerHelp}
          onPointerDown={startDrag}
          onPointerMove={drag}
          onPointerUp={finishDrag}
          onPointerCancel={cancelDrag}
          onLostPointerCapture={cancelDrag}
          onDoubleClick={resetFromPointer}
        >
          <img
            src={originalImageUrl(currentSource)}
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

function normalizeImageSources(src: string, sources?: readonly string[]): string[] {
  const normalized = Array.from(
    new Set((sources ?? [src]).filter((source) => source.trim().length > 0)),
  );
  if (!normalized.includes(src)) normalized.push(src);
  return normalized.length === 0 ? [src] : normalized;
}

function selectedImageIndex(src: string, sources: readonly string[]): number {
  const index = sources.indexOf(src);
  return index < 0 ? 0 : index;
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
