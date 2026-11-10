import { type CdpClient, waitForPageCondition } from "./cdp-client";

/** Generated same-origin video from issue-regressions-cdp; no real X media or account. */
export async function verifyOpenVideoIssues(
  client: CdpClient,
  click: (client: CdpClient, selector: string) => Promise<void>,
): Promise<Record<string, unknown>> {
  const player = '[data-media-id="issue-video"]';
  await client.evaluate(
    `document.querySelector(${JSON.stringify(player)}).scrollIntoView({ block: "center" })`,
  );
  await waitForPageCondition(client, `document.querySelector('${player} video')?.readyState >= 2`);
  await client.evaluate(`(async () => {
    const video = document.querySelector('${player} video');
    window.__issueInlineVideo = video;
    await video.play();
  })()`);
  await click(client, `${player} [data-video-action=mute]`);
  await click(client, `${player} [data-video-action=playback]`);
  await client.evaluate(`(() => {
    const input = document.querySelector('${player} [data-video-action=volume]');
    Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, "value").set.call(input, "23");
    input.dispatchEvent(new Event("input", { bubbles: true }));
    input.dispatchEvent(new Event("change", { bubbles: true }));
    const video = document.querySelector('${player} video');
    video.currentTime = .15;
  })()`);
  const retained = `(() => {
    const player = document.querySelector('${player}');
    const video = player?.querySelector('video');
    return video?.paused === true && video.muted === false &&
      Math.abs(video.volume - .23) < .001 && Math.abs(video.currentTime - .15) < .05;
  })()`;
  await waitForPageCondition(client, retained);
  await click(client, `${player} [data-video-action=fullscreen]`);
  await waitForPageCondition(
    client,
    `document.querySelector('${player}')?.dataset.fullscreen === "true"`,
  );
  await waitForPageCondition(client, retained);
  await client.evaluate(`(() => {
    const quality = document.querySelector('${player} [data-video-action=quality]');
    quality.value = "low";
    quality.dispatchEvent(new Event("change", { bubbles: true }));
  })()`);
  await waitForPageCondition(
    client,
    `document.querySelector('${player} video')?.readyState >= 2 && document.querySelector('${player} video')?.src.includes('quality=low') === true`,
  );
  await waitForPageCondition(client, retained);
  await click(client, `${player} [data-video-action=fullscreen]`);
  await waitForPageCondition(
    client,
    `document.querySelector('${player}')?.dataset.fullscreen === "false"`,
  );
  await waitForPageCondition(client, retained);
  await waitForPageCondition(
    client,
    `document.querySelector('${player} [data-video-action=quality]')?.value === "low"`,
  );
  const clock = await client.evaluate<string>(
    `document.querySelector('${player} .configured-video-time')?.textContent ?? ""`,
  );
  if (!/^\d{2,}:\d{2}(?::\d{2})? \/ \d{2,}:\d{2}(?::\d{2})?$/.test(clock))
    throw new Error(`動画時刻がMM:SS/HH:MM:SS形式ではありません: ${clock}`);

  // A detail creates a second player for the same media ID. Only its actual
  // instance may own playback, and unmounting the old copy must not release it.
  await click(client, `${player} [data-video-action=playback]`);
  await waitForPageCondition(client, `document.querySelector('${player} video')?.paused === false`);
  await client.evaluate(
    `void (window.__issueInlineVideo = document.querySelector('${player} video'))`,
  );
  await click(client, '[data-post-id="issue-video-post"] .post-text');
  const detailPlayer = `.post-detail-content ${player}`;
  await waitForPageCondition(
    client,
    `document.querySelector('${detailPlayer} video')?.readyState >= 2`,
  );
  await click(client, `${detailPlayer} [data-video-action=playback]`);
  await waitForPageCondition(
    client,
    `(() => {
    const players = [...document.querySelectorAll('${player} video')];
    const detail = document.querySelector('${detailPlayer} video');
    return players.length === 2 && detail?.paused === false && window.__issueInlineVideo.paused === true &&
      players.filter(video => !video.paused).length === 1;
  })()`,
  );
  await click(client, `${detailPlayer} [data-video-action=playback]`);
  await client.call("Input.dispatchKeyEvent", { type: "keyDown", key: "Escape" });
  await client.call("Input.dispatchKeyEvent", { type: "keyUp", key: "Escape" });
  await waitForPageCondition(client, 'document.querySelector(".post-detail-content") === null');
  return {
    sameMediaSinglePlaybackOwner: true,
    fullscreenRetainsMuteVolumePositionPauseQuality: true,
    qualityChangeRetainsPlaybackState: true,
    videoClock: clock,
  };
}
