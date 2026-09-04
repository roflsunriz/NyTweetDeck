import {
  Maximize,
  Minimize,
  Pause,
  PictureInPicture2,
  Play,
  Repeat2,
  Volume2,
  VolumeX,
} from "lucide-react";
import {
  type ChangeEvent,
  type PointerEvent as ReactPointerEvent,
  useCallback,
  useEffect,
  useRef,
  useState,
} from "react";
import type { Translation } from "../i18n/translations";
import type { VideoQuality } from "../model/layout";
import type { VideoVariant } from "../model/timeline";
import { selectVideoSource } from "../model/video-quality";

export const VIDEO_CONTROLS_HIDE_DELAY_MS = 3_000;

interface Point {
  x: number;
  y: number;
}

const VIDEO_MIN_ZOOM = 1;
const VIDEO_MAX_ZOOM = 4;

interface ConfiguredVideoProps {
  mediaId: string;
  autoPlay: boolean;
  loop: boolean;
  volume: number;
  poster: string;
  src: string;
  variants?: VideoVariant[];
  quality?: VideoQuality;
  translation: Translation;
}

export function ConfiguredVideo({
  mediaId,
  autoPlay,
  loop,
  volume,
  poster,
  src,
  variants = [],
  quality = "auto",
  translation,
}: ConfiguredVideoProps) {
  const playerRef = useRef<HTMLFieldSetElement | null>(null);
  const videoRef = useRef<HTMLVideoElement | null>(null);
  const loopRef = useRef(loop);
  const intersectionActiveRef = useRef(false);
  const pictureInPictureRef = useRef(false);
  const controlsTimerRef = useRef<ReturnType<typeof globalThis.setTimeout> | null>(null);
  const [inPlaybackZone, setInPlaybackZone] = useState(
    () => typeof globalThis.IntersectionObserver === "undefined",
  );
  const [playing, setPlaying] = useState(false);
  const [muted, setMuted] = useState(true);
  const [currentTime, setCurrentTime] = useState(0);
  const [duration, setDuration] = useState(0);
  const [playbackVolume, setPlaybackVolume] = useState(() => normalizeVolume(volume));
  const [loopActive, setLoopActive] = useState(loop);
  const [fullscreen, setFullscreen] = useState(false);
  const [pictureInPicture, setPictureInPicture] = useState(false);
  const [pictureInPictureAvailable, setPictureInPictureAvailable] = useState(false);
  const [controlError, setControlError] = useState<string | null>(null);
  const [controlsVisible, setControlsVisible] = useState(true);
  const [selectedQuality, setSelectedQuality] = useState<VideoQuality>(quality);
  const selectedSource = selectVideoSource(src, variants, selectedQuality);
  const [videoZoom, setVideoZoom] = useState(1);
  const [videoPan, setVideoPan] = useState<Point>({ x: 0, y: 0 });
  const videoPointersRef = useRef<Map<number, Point>>(new Map());
  const videoPinchRef = useRef<{
    distance: number;
    center: Point;
    zoom: number;
    pan: Point;
  } | null>(null);

  useEffect(() => {
    setSelectedQuality(quality);
  }, [quality]);

  // biome-ignore lint/correctness/useExhaustiveDependencies: reset zoom when video source changes
  useEffect(() => {
    setVideoZoom(1);
    setVideoPan({ x: 0, y: 0 });
    videoPointersRef.current.clear();
    videoPinchRef.current = null;
  }, [selectedSource]);

  const hideControlsIfKeyboardIdle = useCallback(() => {
    controlsTimerRef.current = null;
    if (playerRef.current?.querySelector(":focus-visible") !== null) return;
    setControlsVisible(false);
  }, []);

  const revealControls = useCallback(() => {
    if (controlsTimerRef.current !== null) globalThis.clearTimeout(controlsTimerRef.current);
    setControlsVisible(true);
    if (!inPlaybackZone) return;
    controlsTimerRef.current = globalThis.setTimeout(
      hideControlsIfKeyboardIdle,
      VIDEO_CONTROLS_HIDE_DELAY_MS,
    );
  }, [hideControlsIfKeyboardIdle, inPlaybackZone]);

  const handleVideoPointerDown = useCallback(
    (event: ReactPointerEvent<HTMLVideoElement>) => {
      revealControls();
      const point: Point = {
        x: finiteCoordinate(event.clientX),
        y: finiteCoordinate(event.clientY),
      };
      videoPointersRef.current.set(event.pointerId, point);
      try {
        event.currentTarget.setPointerCapture(event.pointerId);
      } catch {
        // ignore
      }
      if (videoPointersRef.current.size === 2) {
        const pts = [...videoPointersRef.current.values()];
        const a = pts[0];
        const b = pts[1];
        if (a === undefined || b === undefined) return;
        const distance = Math.hypot(
          finiteCoordinate(a.x) - finiteCoordinate(b.x),
          finiteCoordinate(a.y) - finiteCoordinate(b.y),
        );
        const center: Point = {
          x: (finiteCoordinate(a.x) + finiteCoordinate(b.x)) / 2,
          y: (finiteCoordinate(a.y) + finiteCoordinate(b.y)) / 2,
        };
        if (distance > 0) {
          videoPinchRef.current = { distance, center, zoom: videoZoom, pan: videoPan };
        }
        event.preventDefault();
      }
    },
    [revealControls, videoPan, videoZoom],
  );

  const handleVideoPointerMove = useCallback((event: ReactPointerEvent<HTMLVideoElement>) => {
    if (!videoPointersRef.current.has(event.pointerId)) return;
    const point: Point = {
      x: finiteCoordinate(event.clientX),
      y: finiteCoordinate(event.clientY),
    };
    videoPointersRef.current.set(event.pointerId, point);
    if (videoPointersRef.current.size === 2 && videoPinchRef.current !== null) {
      const pts = [...videoPointersRef.current.values()];
      const a = pts[0];
      const b = pts[1];
      if (a === undefined || b === undefined) return;
      const nextDistance = Math.hypot(
        finiteCoordinate(a.x) - finiteCoordinate(b.x),
        finiteCoordinate(a.y) - finiteCoordinate(b.y),
      );
      const nextCenter: Point = {
        x: (finiteCoordinate(a.x) + finiteCoordinate(b.x)) / 2,
        y: (finiteCoordinate(a.y) + finiteCoordinate(b.y)) / 2,
      };
      const baseline = videoPinchRef.current;
      if (baseline.distance <= 0 || nextDistance <= 0) return;
      const ratio = nextDistance / baseline.distance;
      const nextZoom = Math.min(VIDEO_MAX_ZOOM, Math.max(VIDEO_MIN_ZOOM, baseline.zoom * ratio));
      const bounds = event.currentTarget.getBoundingClientRect();
      const viewportCenter: Point = {
        x: finiteCoordinate(bounds.left) + finiteCoordinate(bounds.width) / 2,
        y: finiteCoordinate(bounds.top) + finiteCoordinate(bounds.height) / 2,
      };
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
      setVideoZoom(nextZoom);
      setVideoPan(anchoredPan);
      event.preventDefault();
    } else if (videoPointersRef.current.size === 2) {
      event.preventDefault();
    }
  }, []);

  const handleVideoPointerUp = useCallback((event: ReactPointerEvent<HTMLVideoElement>) => {
    videoPointersRef.current.delete(event.pointerId);
    if (videoPinchRef.current !== null && videoPointersRef.current.size < 2) {
      videoPinchRef.current = null;
    }
    try {
      event.currentTarget.releasePointerCapture(event.pointerId);
    } catch {
      // ignore
    }
  }, []);

  const handleVideoDoubleClick = useCallback(() => {
    setVideoZoom(1);
    setVideoPan({ x: 0, y: 0 });
    videoPointersRef.current.clear();
    videoPinchRef.current = null;
  }, []);

  useEffect(() => {
    const player = playerRef.current;
    if (player === null || typeof globalThis.IntersectionObserver === "undefined") return;
    setInPlaybackZone(false);
    const observer = new IntersectionObserver(
      (entries) => {
        const active = entries.some((entry) => entry.target === player && entry.isIntersecting);
        intersectionActiveRef.current = active;
        if (!active && pictureInPictureRef.current) return;
        setInPlaybackZone(active);
        if (!active) {
          setPlaying(false);
          setMuted(true);
          setCurrentTime(0);
          setDuration(0);
          setLoopActive(loopRef.current);
          setPictureInPicture(false);
          setControlError(null);
        }
      },
      {
        root: null,
        rootMargin: "0px 0px -50% 0px",
        threshold: 0,
      },
    );
    observer.observe(player);
    return () => observer.disconnect();
  }, []);

  useEffect(() => {
    if (controlsTimerRef.current !== null) globalThis.clearTimeout(controlsTimerRef.current);
    if (!inPlaybackZone) {
      controlsTimerRef.current = null;
      setControlsVisible(false);
      return;
    }
    setControlsVisible(true);
    controlsTimerRef.current = globalThis.setTimeout(
      hideControlsIfKeyboardIdle,
      VIDEO_CONTROLS_HIDE_DELAY_MS,
    );
    return () => {
      if (controlsTimerRef.current !== null) globalThis.clearTimeout(controlsTimerRef.current);
      controlsTimerRef.current = null;
    };
  }, [hideControlsIfKeyboardIdle, inPlaybackZone]);

  useEffect(() => {
    const normalized = normalizeVolume(volume);
    setPlaybackVolume(normalized);
    if (!inPlaybackZone) return;
    const video = videoRef.current;
    if (video !== null) video.volume = normalized;
  }, [volume, inPlaybackZone]);

  useEffect(() => {
    loopRef.current = loop;
    setLoopActive(loop);
  }, [loop]);

  useEffect(() => {
    const onFullscreenChange = () =>
      setFullscreen(document.fullscreenElement === playerRef.current);
    document.addEventListener("fullscreenchange", onFullscreenChange);
    return () => document.removeEventListener("fullscreenchange", onFullscreenChange);
  }, []);

  useEffect(() => {
    if (!inPlaybackZone) {
      setPictureInPictureAvailable(false);
      return;
    }
    const video = videoRef.current;
    if (video === null) return;
    setPictureInPictureAvailable(
      document.pictureInPictureEnabled === true &&
        typeof video.requestPictureInPicture === "function",
    );
    const enter = () => {
      pictureInPictureRef.current = true;
      setPictureInPicture(true);
    };
    const leave = () => {
      pictureInPictureRef.current = false;
      setPictureInPicture(false);
      if (
        !intersectionActiveRef.current &&
        typeof globalThis.IntersectionObserver !== "undefined"
      ) {
        setInPlaybackZone(false);
      }
    };
    video.addEventListener("enterpictureinpicture", enter);
    video.addEventListener("leavepictureinpicture", leave);
    return () => {
      video.removeEventListener("enterpictureinpicture", enter);
      video.removeEventListener("leavepictureinpicture", leave);
      video.pause();
      video.removeAttribute("src");
      video.load();
    };
  }, [inPlaybackZone]);

  useEffect(() => {
    if (!inPlaybackZone) return;
    const video = videoRef.current;
    if (video === null) return;
    video.muted = muted;
    video.loop = loopActive;
    video.volume = playbackVolume;
  }, [inPlaybackZone, loopActive, muted, playbackVolume]);

  useEffect(() => {
    if (!inPlaybackZone) return;
    const video = videoRef.current;
    if (video === null) return;
    if (!autoPlay) {
      video.pause();
      return;
    }
    void tryPlay(video);
  }, [autoPlay, inPlaybackZone]);

  const togglePlayback = () => {
    const video = videoRef.current;
    if (video === null) return;
    if (video.paused) void tryPlay(video);
    else video.pause();
  };

  const toggleMute = () => {
    const video = videoRef.current;
    if (video === null) return;
    const next = !video.muted;
    video.muted = next;
    setMuted(next);
  };

  const unmuteForDirectControl = () => {
    const video = videoRef.current;
    if (video === null || !video.muted) return;
    video.muted = false;
    setMuted(false);
  };

  const seek = (event: ChangeEvent<HTMLInputElement>) => {
    const video = videoRef.current;
    if (video === null) return;
    const next = Number(event.currentTarget.value);
    if (!Number.isFinite(next)) return;
    unmuteForDirectControl();
    video.currentTime = next;
    setCurrentTime(next);
  };

  const changeVolume = (event: ChangeEvent<HTMLInputElement>) => {
    const video = videoRef.current;
    if (video === null) return;
    const next = normalizeVolume(Number(event.currentTarget.value));
    video.volume = next;
    setPlaybackVolume(next);
    unmuteForDirectControl();
  };

  const toggleFullscreen = async () => {
    const player = playerRef.current;
    if (player === null) return;
    setControlError(null);
    try {
      if (document.fullscreenElement === player) await document.exitFullscreen();
      else await player.requestFullscreen();
    } catch {
      setFullscreen(document.fullscreenElement === player);
      setControlError(translation.videoControlFailed);
    }
  };

  const togglePictureInPicture = async () => {
    const video = videoRef.current;
    if (video === null || !pictureInPictureAvailable) return;
    setControlError(null);
    try {
      if (document.pictureInPictureElement === video) await document.exitPictureInPicture();
      else await video.requestPictureInPicture();
    } catch {
      setPictureInPicture(document.pictureInPictureElement === video);
      setControlError(translation.videoControlFailed);
    }
  };

  return (
    <fieldset
      ref={playerRef}
      className="configured-video"
      aria-label={translation.videoPlayer}
      data-media-id={mediaId}
      data-viewport-active={inPlaybackZone}
      data-controls-visible={controlsVisible}
      data-zoom={videoZoom}
      onPointerMove={revealControls}
      onFocusCapture={revealControls}
      onBlurCapture={revealControls}
    >
      {inPlaybackZone ? (
        <video
          key={selectedSource}
          ref={videoRef}
          muted={muted}
          autoPlay={autoPlay}
          loop={loopActive}
          draggable={false}
          playsInline
          preload="metadata"
          poster={poster || undefined}
          src={selectedSource || undefined}
          style={
            videoZoom === 1
              ? undefined
              : {
                  transform: `translate(${videoPan.x}px, ${videoPan.y}px) scale(${videoZoom})`,
                  transformOrigin: "center",
                  willChange: "transform",
                }
          }
          onClick={() => {
            if (videoZoom !== 1) {
              handleVideoDoubleClick();
              return;
            }
            if (!controlsVisible) {
              revealControls();
              return;
            }
            togglePlayback();
          }}
          onPointerMove={handleVideoPointerMove}
          onPointerDown={handleVideoPointerDown}
          onPointerUp={handleVideoPointerUp}
          onPointerCancel={handleVideoPointerUp}
          onDoubleClick={handleVideoDoubleClick}
          onDragStart={(event) => event.preventDefault()}
          onPlay={() => setPlaying(true)}
          onPause={() => setPlaying(false)}
          onLoadedMetadata={(event) => {
            const next = Number.isFinite(event.currentTarget.duration)
              ? event.currentTarget.duration
              : 0;
            setDuration(next);
          }}
          onDurationChange={(event) => {
            const next = Number.isFinite(event.currentTarget.duration)
              ? event.currentTarget.duration
              : 0;
            setDuration(next);
          }}
          onTimeUpdate={(event) => setCurrentTime(event.currentTarget.currentTime)}
        />
      ) : poster ? (
        <img className="configured-video-poster" loading="lazy" src={poster} alt="" />
      ) : (
        <span className="configured-video-poster" aria-hidden="true" />
      )}
      {inPlaybackZone && (
        <div
          className="configured-video-controls"
          data-playing={playing}
          data-visible={controlsVisible}
        >
          <button
            type="button"
            aria-label={playing ? translation.pauseVideo : translation.playVideo}
            onClick={togglePlayback}
          >
            {playing ? (
              <Pause aria-hidden="true" size={16} />
            ) : (
              <Play aria-hidden="true" size={16} />
            )}
          </button>
          <output className="configured-video-time">
            {formatTime(currentTime)} / {formatTime(duration)}
          </output>
          <input
            className="configured-video-seek"
            type="range"
            aria-label={translation.videoSeek}
            min={0}
            max={duration > 0 ? duration : 0}
            step={0.1}
            value={duration > 0 ? Math.min(currentTime, duration) : 0}
            onPointerDown={unmuteForDirectControl}
            onChange={seek}
          />
          <button
            type="button"
            aria-label={muted ? translation.unmuteVideo : translation.muteVideo}
            aria-pressed={muted}
            onClick={toggleMute}
          >
            {muted ? (
              <VolumeX aria-hidden="true" size={16} />
            ) : (
              <Volume2 aria-hidden="true" size={16} />
            )}
          </button>
          <input
            className="configured-video-volume"
            type="range"
            aria-label={translation.videoVolume}
            min={0}
            max={100}
            step={1}
            value={Math.round(playbackVolume * 100)}
            onPointerDown={unmuteForDirectControl}
            onChange={changeVolume}
          />
          {variants.length > 1 && (
            <select
              className="configured-video-quality"
              aria-label={translation.videoQuality}
              value={selectedQuality}
              onClick={(event) => event.stopPropagation()}
              onChange={(event) => setSelectedQuality(event.target.value as VideoQuality)}
            >
              <option value="auto">{translation.videoQualityAuto}</option>
              <option value="low">{translation.videoQualityLow}</option>
              <option value="medium">{translation.videoQualityMedium}</option>
              <option value="high">{translation.videoQualityHigh}</option>
            </select>
          )}
          <button
            type="button"
            aria-label={translation.videoLoop}
            aria-pressed={loopActive}
            onClick={() => setLoopActive((current) => !current)}
          >
            <Repeat2 aria-hidden="true" size={16} />
          </button>
          {pictureInPictureAvailable && (
            <button
              type="button"
              aria-label={
                pictureInPicture ? translation.exitPictureInPicture : translation.pictureInPicture
              }
              aria-pressed={pictureInPicture}
              onClick={() => void togglePictureInPicture()}
            >
              <PictureInPicture2 aria-hidden="true" size={16} />
            </button>
          )}
          <button
            type="button"
            aria-label={fullscreen ? translation.exitFullscreen : translation.enterFullscreen}
            aria-pressed={fullscreen}
            onClick={() => void toggleFullscreen()}
          >
            {fullscreen ? (
              <Minimize aria-hidden="true" size={16} />
            ) : (
              <Maximize aria-hidden="true" size={16} />
            )}
          </button>
          {controlError !== null && (
            <span className="configured-video-error" role="status">
              {controlError}
            </span>
          )}
        </div>
      )}
    </fieldset>
  );
}

function finiteCoordinate(value: number): number {
  return Number.isFinite(value) ? value : 0;
}

function normalizeVolume(volume: number): number {
  return Math.min(100, Math.max(0, volume)) / 100;
}

async function tryPlay(video: HTMLVideoElement): Promise<void> {
  try {
    await video.play();
  } catch (error: unknown) {
    if (
      !(error instanceof DOMException) ||
      (error.name !== "AbortError" && error.name !== "NotAllowedError")
    ) {
      video.pause();
    }
  }
}

function formatTime(seconds: number): string {
  if (!Number.isFinite(seconds) || seconds < 0) return "0:00";
  const wholeSeconds = Math.floor(seconds);
  const minutes = Math.floor(wholeSeconds / 60);
  const remainder = wholeSeconds % 60;
  return `${minutes}:${String(remainder).padStart(2, "0")}`;
}
