import { type ChangeEvent, type FormEvent, useEffect, useRef, useState } from "react";
import { composerLabels } from "../i18n/composer-labels";
import type { Translation } from "../i18n/translations";

interface ComposerFormProps {
  translation: Translation;
  accountId: string;
  inReplyToPostId?: string;
  quotePostId?: string;
  quotePostUrl?: string;
  onClose: () => void;
  onPublished?: () => void;
}

interface PlaceOption {
  id: string;
  name: string;
  country: string | null;
  geoSearchRequestId?: string | null;
}

interface PollChoice {
  id: number;
  text: string;
}

const emojiChoices = [
  "😀",
  "😂",
  "🥹",
  "😍",
  "😊",
  "😎",
  "🤔",
  "😭",
  "❤️",
  "✨",
  "🔥",
  "👍",
  "🙏",
  "🎉",
  "🌸",
  "💡",
];
const pollDurations = [60, 360, 1440, 4320, 10080] as const;

export function ComposerForm({
  translation,
  accountId,
  inReplyToPostId,
  quotePostId,
  quotePostUrl,
  onClose,
  onPublished,
}: ComposerFormProps) {
  const labels = composerLabels(document.documentElement.lang || "ja");
  const textareaRef = useRef<HTMLTextAreaElement>(null);
  const imageInputRef = useRef<HTMLInputElement>(null);
  const gifInputRef = useRef<HTMLInputElement>(null);
  const nextChoiceId = useRef(0);
  const newChoice = (): PollChoice => ({ id: nextChoiceId.current++, text: "" });
  const [text, setText] = useState("");
  const [files, setFiles] = useState<File[]>([]);
  const [pollChoices, setPollChoices] = useState<PollChoice[] | null>(null);
  const [pollDuration, setPollDuration] = useState(1440);
  const [showEmoji, setShowEmoji] = useState(false);
  const [scheduledAt, setScheduledAt] = useState("");
  const [showPlaceSearch, setShowPlaceSearch] = useState(false);
  const [placeQuery, setPlaceQuery] = useState("");
  const [placeOptions, setPlaceOptions] = useState<PlaceOption[]>([]);
  const [place, setPlace] = useState<PlaceOption | null>(null);
  const [showDisclosure, setShowDisclosure] = useState(false);
  const [paidPartnership, setPaidPartnership] = useState(false);
  const [aiGenerated, setAiGenerated] = useState(false);
  const [publishing, setPublishing] = useState(false);
  const [scheduledSuccessfully, setScheduledSuccessfully] = useState(false);
  const [searchingPlaces, setSearchingPlaces] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const isTargeted = inReplyToPostId !== undefined || quotePostId !== undefined;
  const canSubmit = text.trim().length > 0 || files.length > 0 || pollChoices !== null;

  const addFiles = (event: ChangeEvent<HTMLInputElement>, kind: "image" | "gif") => {
    const selected = Array.from(event.target.files ?? []);
    event.target.value = "";
    if (selected.length === 0) return;
    const next = (kind === "gif" ? selected : [...files, ...selected]).filter(
      (file, index, all) =>
        all.findIndex(
          (candidate) =>
            candidate.name === file.name &&
            candidate.size === file.size &&
            candidate.lastModified === file.lastModified,
        ) === index,
    );
    const valid =
      next.length <= 4 &&
      (kind === "gif"
        ? next.length === 1 && next[0]?.type === "image/gif"
        : next.every((file) => file.type === "image/jpeg" || file.type === "image/png")) &&
      pollChoices === null;
    if (!valid) {
      setError(labels.invalidSelection);
      return;
    }
    setFiles(next);
    setError(null);
  };

  const addEmoji = (emoji: string) => {
    const input = textareaRef.current;
    const start = input?.selectionStart ?? text.length;
    const end = input?.selectionEnd ?? start;
    const updated = text.slice(0, start) + emoji + text.slice(end);
    if (updated.length > 4000) return;
    setText(updated);
    setShowEmoji(false);
    queueMicrotask(() => {
      input?.focus();
      input?.setSelectionRange(start + emoji.length, start + emoji.length);
    });
  };

  const searchPlaces = async () => {
    if (placeQuery.trim().length === 0) return;
    setSearchingPlaces(true);
    setError(null);
    try {
      const params = new URLSearchParams({ accountId, query: placeQuery.trim() });
      const response = await fetch(`/api/v1/posts/places?${params}`);
      if (!response.ok) throw new Error("place search failed");
      const options = (await response.json()) as PlaceOption[];
      setPlaceOptions(options);
    } catch {
      setError(labels.placeSearchFailed);
    } finally {
      setSearchingPlaces(false);
    }
  };

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    if (!canSubmit || publishing) return;
    if (
      pollChoices !== null &&
      (pollChoices.length < 2 || pollChoices.some((choice) => choice.text.trim().length === 0))
    ) {
      setError(labels.invalidPoll);
      return;
    }
    const scheduledDate = scheduledAt === "" ? null : new Date(scheduledAt);
    if (
      scheduledDate !== null &&
      (!Number.isFinite(scheduledDate.getTime()) || scheduledDate.getTime() <= Date.now() + 120_000)
    ) {
      setError(labels.invalidSchedule);
      return;
    }
    setPublishing(true);
    setError(null);
    try {
      const mediaIds: string[] = [];
      for (const file of files) {
        const form = new FormData();
        form.set("accountId", accountId);
        form.set("file", file);
        const upload = await fetch("/api/v1/posts/media", { method: "POST", body: form });
        if (!upload.ok) throw new Error(await problemDetail(upload, labels.uploadFailed));
        const result = (await upload.json()) as { mediaId?: unknown };
        if (typeof result.mediaId !== "string") throw new Error(labels.uploadFailed);
        mediaIds.push(result.mediaId);
      }
      const response = await fetch("/api/v1/posts", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          accountId,
          text: text.trim(),
          inReplyToPostId,
          quotePostId,
          mediaIds: mediaIds.length > 0 ? mediaIds : undefined,
          poll:
            pollChoices === null
              ? undefined
              : {
                  choices: pollChoices.map((choice) => choice.text.trim()),
                  durationMinutes: pollDuration,
                },
          scheduledAt: scheduledDate?.toISOString(),
          place:
            place === null
              ? undefined
              : {
                  id: place.id,
                  geoSearchRequestId: place.geoSearchRequestId ?? undefined,
                },
          paidPartnership,
          aiGenerated,
        }),
      });
      if (!response.ok) throw new Error(await problemDetail(response, translation.postFailed));
      if (scheduledDate === null) {
        onPublished?.();
        onClose();
      } else {
        setScheduledSuccessfully(true);
      }
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : translation.postFailed);
    } finally {
      setPublishing(false);
    }
  };

  if (scheduledSuccessfully) {
    return (
      <div className="composer-message" role="status">
        <p>{labels.scheduledSuccess}</p>
        <button className="secondary-button" type="button" onClick={onClose}>
          {translation.close}
        </button>
      </div>
    );
  }

  return (
    <form className="composer-form" onSubmit={submit}>
      {quotePostUrl !== undefined && (
        <a className="quote-preview-link" href={quotePostUrl} target="_blank" rel="noreferrer">
          {translation.quotingPost}
        </a>
      )}
      <textarea
        ref={textareaRef}
        maxLength={4000}
        placeholder={translation.postPlaceholder}
        value={text}
        onChange={(event) => setText(event.target.value)}
        disabled={publishing}
      />
      {files.length > 0 && (
        <div className="composer-media-list">
          {files.map((file) => (
            <MediaPreview
              key={`${file.name}-${file.size}-${file.lastModified}`}
              file={file}
              onRemove={() =>
                setFiles((current) => current.filter((candidate) => candidate !== file))
              }
              removeLabel={labels.remove}
            />
          ))}
        </div>
      )}
      <fieldset className="composer-tools">
        <legend>{translation.composeTitle}</legend>
        <input
          ref={imageInputRef}
          type="file"
          accept="image/jpeg,image/png"
          multiple
          hidden
          onChange={(event) => addFiles(event, "image")}
        />
        <input
          ref={gifInputRef}
          type="file"
          accept="image/gif"
          hidden
          onChange={(event) => addFiles(event, "gif")}
        />
        <button
          type="button"
          disabled={
            publishing || pollChoices !== null || files.some((file) => file.type === "image/gif")
          }
          onClick={() => imageInputRef.current?.click()}
        >
          {labels.image}
        </button>
        <button
          type="button"
          disabled={publishing || pollChoices !== null}
          onClick={() => gifInputRef.current?.click()}
        >
          {labels.gif}
        </button>
        <button
          type="button"
          disabled={publishing || isTargeted || files.length > 0 || scheduledAt !== ""}
          aria-pressed={pollChoices !== null}
          onClick={() =>
            setPollChoices((current) => (current === null ? [newChoice(), newChoice()] : null))
          }
        >
          {labels.poll}
        </button>
        <button
          type="button"
          disabled={publishing}
          aria-expanded={showEmoji}
          onClick={() => setShowEmoji((current) => !current)}
        >
          {labels.emoji}
        </button>
        <button
          type="button"
          disabled={publishing || isTargeted || pollChoices !== null || place !== null}
          aria-pressed={scheduledAt !== ""}
          onClick={() => {
            setScheduledAt((current) => (current === "" ? nextScheduleTime() : ""));
            setShowPlaceSearch(false);
            setPlaceOptions([]);
          }}
        >
          {labels.schedule}
        </button>
        <button
          type="button"
          disabled={publishing || scheduledAt !== ""}
          aria-expanded={showPlaceSearch}
          onClick={() => setShowPlaceSearch((current) => !current)}
        >
          {labels.place}
        </button>
        <button
          type="button"
          disabled={publishing}
          aria-expanded={showDisclosure}
          onClick={() => setShowDisclosure((current) => !current)}
        >
          {labels.disclosure}
        </button>
      </fieldset>
      {showEmoji && (
        <fieldset className="composer-emoji-grid">
          <legend>{labels.emoji}</legend>
          {emojiChoices.map((emoji) => (
            <button key={emoji} type="button" onClick={() => addEmoji(emoji)}>
              {emoji}
            </button>
          ))}
        </fieldset>
      )}
      {pollChoices !== null && (
        <fieldset className="composer-options">
          <legend>{labels.choices}</legend>
          {pollChoices.map((choice, index) => (
            <div className="composer-choice" key={choice.id}>
              <input
                aria-label={labels.choice(index + 1)}
                maxLength={25}
                value={choice.text}
                disabled={publishing}
                onChange={(event) =>
                  setPollChoices(
                    (current) =>
                      current?.map((item) =>
                        item.id === choice.id ? { ...item, text: event.target.value } : item,
                      ) ?? null,
                  )
                }
              />
              {index > 1 && (
                <button
                  type="button"
                  onClick={() =>
                    setPollChoices(
                      (current) => current?.filter((item) => item.id !== choice.id) ?? null,
                    )
                  }
                >
                  {labels.remove}
                </button>
              )}
            </div>
          ))}
          {pollChoices.length < 4 && (
            <button
              type="button"
              onClick={() =>
                setPollChoices((current) => (current === null ? null : [...current, newChoice()]))
              }
            >
              {labels.addChoice}
            </button>
          )}
          <label>
            {labels.duration}
            <select
              value={pollDuration}
              disabled={publishing}
              onChange={(event) => setPollDuration(Number(event.target.value))}
            >
              {pollDurations.map((duration, index) => (
                <option key={duration} value={duration}>
                  {
                    [
                      labels.oneHour,
                      labels.sixHours,
                      labels.oneDay,
                      labels.threeDays,
                      labels.sevenDays,
                    ][index]
                  }
                </option>
              ))}
            </select>
          </label>
        </fieldset>
      )}
      {scheduledAt !== "" && (
        <label className="composer-options">
          {labels.scheduledFor}
          <input
            type="datetime-local"
            value={scheduledAt}
            disabled={publishing}
            onChange={(event) => setScheduledAt(event.target.value)}
          />
        </label>
      )}
      {showPlaceSearch && (
        <div className="composer-options">
          <label>
            {labels.searchPlaces}
            <input
              value={placeQuery}
              placeholder={labels.placePlaceholder}
              disabled={publishing}
              onChange={(event) => setPlaceQuery(event.target.value)}
            />
          </label>
          <button
            type="button"
            disabled={searchingPlaces || publishing}
            onClick={() => void searchPlaces()}
          >
            {labels.search}
          </button>
          {place !== null && (
            <button type="button" onClick={() => setPlace(null)}>
              {place.name} ×
            </button>
          )}
          {placeOptions.length > 0 && (
            <div className="composer-place-results">
              {placeOptions.map((option) => (
                <button
                  key={option.id}
                  type="button"
                  onClick={() => {
                    setPlace(option);
                    setPlaceOptions([]);
                  }}
                >
                  {option.name}
                </button>
              ))}
            </div>
          )}
        </div>
      )}
      {showDisclosure && (
        <fieldset className="composer-options">
          <legend>{labels.disclosure}</legend>
          <label>
            <input
              type="checkbox"
              checked={paidPartnership}
              disabled={publishing}
              onChange={(event) => setPaidPartnership(event.target.checked)}
            />
            {labels.paidPartnership}
          </label>
          <label>
            <input
              type="checkbox"
              checked={aiGenerated}
              disabled={publishing}
              onChange={(event) => setAiGenerated(event.target.checked)}
            />
            {labels.aiGenerated}
          </label>
        </fieldset>
      )}
      <div className="composer-footer">
        <span>{text.length}/4000</span>
        <button className="primary-button" type="submit" disabled={publishing || !canSubmit}>
          {publishing
            ? translation.publishing
            : scheduledAt === ""
              ? translation.publishPost
              : labels.schedulePost}
        </button>
      </div>
      {error !== null && (
        <p className="setup-error" role="alert">
          {error}
        </p>
      )}
    </form>
  );
}

function MediaPreview({
  file,
  onRemove,
  removeLabel,
}: {
  file: File;
  onRemove: () => void;
  removeLabel: string;
}) {
  const [url, setUrl] = useState<string | null>(null);
  useEffect(() => {
    if (typeof URL.createObjectURL !== "function") return;
    const objectUrl = URL.createObjectURL(file);
    setUrl(objectUrl);
    return () => URL.revokeObjectURL(objectUrl);
  }, [file]);
  return (
    <div className="composer-media-item">
      {url !== null && <img src={url} alt="" />}
      <span>{file.name}</span>
      <button type="button" onClick={onRemove} aria-label={`${removeLabel} ${file.name}`}>
        ×
      </button>
    </div>
  );
}

function nextScheduleTime(): string {
  const next = new Date(Date.now() + 60 * 60 * 1000);
  const local = new Date(next.getTime() - next.getTimezoneOffset() * 60_000);
  return local.toISOString().slice(0, 16);
}

async function problemDetail(response: Response, fallback: string): Promise<string> {
  try {
    const problem = (await response.json()) as { detail?: unknown };
    if (typeof problem.detail === "string" && problem.detail.length > 0) return problem.detail;
  } catch {
    // An empty or invalid problem body uses the localized fallback.
  }
  return fallback;
}
