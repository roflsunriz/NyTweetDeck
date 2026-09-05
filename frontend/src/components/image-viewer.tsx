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
  const pointersRef = useRef<Map<number, Point>>(new Map());
  const pinchRef = useRef<{
    distance: number;
    center: Point;
    zoom: number;
    pan: Point;
  } | null>(null);
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

  const clearPinch = useCallback(() => {
    pinchRef.current = null;
  }, []);

  const navigate = useCallback(
    (offset: number) => {
      if (imageSources.length <= 1) return;
      const next = imageIndex + offset;
      if (next < 0 || next >= imageSources.length) return;
      setImageIndex(next);
      reset();
    },
    [imageIndex, imageSources.length, reset],
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
    const point = pointerPoint(event);
    pointersRef.current.set(event.pointerId, point);
    try {
      event.currentTarget.setPointerCapture(event.pointerId);
    } catch {
      // Ignore missing capture support
    }

    if (pointersRef.current.size === 2) {
      // Begin pinch: use the two active pointer positions
      const entries = [...pointersRef.current.values()];
      const a = entries[0];
      const b = entries[1];
      if (a === undefined || b === undefined) return;
      const distance = pointDistance(a, b);
      const center = pointMidpoint(a, b);
      if (distance > 0) {
        // Stop single-finger drag when second finger lands
        if (dragRef.current !== null) {
          const active = dragRef.current;
          if (event.currentTarget.hasPointerCapture(active.pointerId)) {
            event.currentTarget.releasePointerCapture(active.pointerId);
          }
          dragRef.current = null;
          setDragging(false);
        }
        pinchRef.current = { distance, center, zoom, pan };
      }
      event.preventDefault();
      return;
    }

    if (pointersRef.current.size > 2) {
      event.preventDefault();
      return;
    }

    if (event.button !== 0 || dragRef.current !== null) return;
    // Allow secondary touch pointer as first pointer for pinch initiation
    if (event.pointerType !== "touch" && event.isPrimary === false) return;
    event.preventDefault();
    dragRef.current = { pointerId: event.pointerId, point, origin: point };
    setDragging(true);
  };

  const drag = (event: ReactPointerEvent<HTMLDivElement>) => {
    const point = pointerPoint(event);
    if (pointersRef.current.has(event.pointerId)) {
      pointersRef.current.set(event.pointerId, point);
    }

    if (pointersRef.current.size === 2 && pinchRef.current !== null) {
      const entries = [...pointersRef.current.values()];
      const a = entries[0];
      const b = entries[1];
      if (a === undefined || b === undefined) return;
      const nextDistance = pointDistance(a, b);
      const nextCenter = pointMidpoint(a, b);
      const baseline = pinchRef.current;
      if (baseline.distance <= 0 || nextDistance <= 0) return;
      const ratio = nextDistance / baseline.distance;
      const nextZoom = Math.min(MAX_ZOOM, Math.max(MIN_ZOOM, baseline.zoom * ratio));
      const bounds = event.currentTarget.getBoundingClientRect();
      const viewportCenter: Point = {
        x: finiteCoordinate(bounds.left) + finiteCoordinate(bounds.width) / 2,
        y: finiteCoordinate(bounds.top) + finiteCoordinate(bounds.height) / 2,
      };
      // Keep the pinch center anchored under the fingers
      const anchoredPan: Point = {
        x:
          finiteCoordinate(nextCenter.x) -
          finiteCoordinate(viewportCenter.x) -
          (finiteCoordinate(baseline.center.x) -
            finiteCoordinate(viewportCenter.x) -
            finiteCoordinate(baseline.pan.x)) *
            (nextZoom / baseline.zoom),
        y:
          finiteCoordinate(nextCenter.y) -
          finiteCoordinate(viewportCenter.y) -
          (finiteCoordinate(baseline.center.y) -
            finiteCoordinate(viewportCenter.y) -
            finiteCoordinate(baseline.pan.y)) *
            (nextZoom / baseline.zoom),
      };
      setZoom(nextZoom);
      setPan(anchoredPan);
      event.preventDefault();
      return;
    }

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
    const hadPinch = pinchRef.current !== null;
    pointersRef.current.delete(event.pointerId);
    if (pinchRef.current !== null) {
      if (pointersRef.current.size < 2) {
        pinchRef.current = null;
      }
      if (event.currentTarget.hasPointerCapture(event.pointerId)) {
        try {
          event.currentTarget.releasePointerCapture(event.pointerId);
        } catch {
          // ignore
        }
      }
      // When one finger remains after pinch, restart drag from its current position
      if (pointersRef.current.size === 1) {
        const remaining = [...pointersRef.current.entries()][0];
        if (remaining !== undefined) {
          const [remainingId, remainingPoint] = remaining;
          dragRef.current = {
            pointerId: remainingId,
            point: remainingPoint,
            origin: remainingPoint,
          };
          setDragging(true);
        }
      }
      if (hadPinch) return;
    }

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
    pointersRef.current.delete(event.pointerId);
    if (pointersRef.current.size < 2) clearPinch();
    stopActiveDrag(event.currentTarget, event.pointerId);
  };

  const resetFromPointer = (event: ReactPointerEvent<HTMLDivElement>) => {
    pointersRef.current.clear();
    clearPinch();
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
                disabled={imageIndex === 0}
                onClick={() => navigate(-1)}
              >
                <ChevronLeft aria-hidden="true" size={19} />
              </button>
              <span className="image-viewer-position" aria-live="polite">
                {imageIndex + 1} / {imageSources.length}
              </span>
              <button
                type="button"
                aria-label={translation.nextImage}
                disabled={imageIndex === imageSources.length - 1}
                onClick={() => navigate(1)}
              >
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

function pointDistance(left: Point, right: Point): number {
  return Math.hypot(
    finiteCoordinate(left.x) - finiteCoordinate(right.x),
    finiteCoordinate(left.y) - finiteCoordinate(right.y),
  );
}

function pointMidpoint(left: Point, right: Point): Point {
  return {
    x: (finiteCoordinate(left.x) + finiteCoordinate(right.x)) / 2,
    y: (finiteCoordinate(left.y) + finiteCoordinate(right.y)) / 2,
  };
}

function finiteCoordinate(value: number): number {
  return Number.isFinite(value) ? value : 0;
}
