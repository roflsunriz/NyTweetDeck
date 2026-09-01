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
import { type ChangeEvent, useEffect, useRef, useState } from "react";
import type { Translation } from "../i18n/translations";

interface ConfiguredVideoProps {
  mediaId: string;
  autoPlay: boolean;
  loop: boolean;
  volume: number;
  poster: string;
  src: string;
  translation: Translation;
}

export function ConfiguredVideo({
  mediaId,
  autoPlay,
  loop,
  volume,
  poster,
  src,
  translation,
}: ConfiguredVideoProps) {
  const playerRef = useRef<HTMLFieldSetElement | null>(null);
  const videoRef = useRef<HTMLVideoElement | null>(null);
  const loopRef = useRef(loop);
  const intersectionActiveRef = useRef(false);
  const pictureInPictureRef = useRef(false);
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

  const seek = (event: ChangeEvent<HTMLInputElement>) => {
    const video = videoRef.current;
    if (video === null) return;
    const next = Number(event.currentTarget.value);
    if (!Number.isFinite(next)) return;
    video.currentTime = next;
    setCurrentTime(next);
  };

  const changeVolume = (event: ChangeEvent<HTMLInputElement>) => {
    const video = videoRef.current;
    if (video === null) return;
    const next = normalizeVolume(Number(event.currentTarget.value));
    video.volume = next;
    setPlaybackVolume(next);
    if (next > 0 && video.muted) {
      video.muted = false;
      setMuted(false);
    }
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
    >
      {inPlaybackZone ? (
        <video
          ref={videoRef}
          muted={muted}
          autoPlay={autoPlay}
          loop={loopActive}
          playsInline
          preload="metadata"
          poster={poster || undefined}
          src={src || undefined}
          onClick={togglePlayback}
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
        <div className="configured-video-controls" data-playing={playing}>
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
            onChange={changeVolume}
          />
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
