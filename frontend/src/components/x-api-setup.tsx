import { CheckCircle2, CircleAlert, LoaderCircle } from "lucide-react";
import { type FormEvent, useEffect, useState } from "react";
import type { Translation } from "../i18n/translations";

interface Readiness {
  androidApiVersion: string;
  clientCredentialsAvailable: boolean;
  deviceProfileAvailable: boolean;
}

interface DeviceProfile {
  model: string;
  osVersion: string;
  manufacturer: string;
  brand: string;
  product: string;
  securityPatchLevel: string;
  language: string;
}

const emptyProfile: DeviceProfile = {
  model: "",
  osVersion: "",
  manufacturer: "",
  brand: "",
  product: "",
  securityPatchLevel: "",
  language: "ja",
};

interface XApiSetupProps {
  translation: Translation;
}

export function XApiSetup({ translation }: XApiSetupProps) {
  const [readiness, setReadiness] = useState<Readiness | null>(null);
  const [profile, setProfile] = useState<DeviceProfile>(emptyProfile);
  const [status, setStatus] = useState<"idle" | "loading" | "saving" | "saved" | "error">(
    "loading",
  );
  const [connectivity, setConnectivity] = useState<"idle" | "checking" | "verified" | "error">(
    "idle",
  );
  const [errorMessage, setErrorMessage] = useState<string | null>(null);

  useEffect(() => {
    const controller = new AbortController();
    void loadSetup(controller.signal);
    return () => controller.abort();

    async function loadSetup(signal: AbortSignal) {
      try {
        const [readinessResponse, profileResponse] = await Promise.all([
          fetch("/api/v1/x-api/readiness", { signal }),
          fetch("/api/v1/x-api/device-profile", { signal }),
        ]);
        if (!readinessResponse.ok || !profileResponse.ok) {
          throw new Error("setup response failed");
        }
        const nextReadiness = (await readinessResponse.json()) as Readiness;
        const nextProfile = (await profileResponse.json()) as DeviceProfile | null;
        setReadiness(nextReadiness);
        setProfile(
          nextProfile ?? { ...emptyProfile, language: document.documentElement.lang || "ja" },
        );
        setStatus("idle");
      } catch (error) {
        if (error instanceof DOMException && error.name === "AbortError") {
          return;
        }
        setStatus("error");
        setErrorMessage(translation.setupLoadError);
      }
    }
  }, [translation.setupLoadError]);

  const save = async (event: FormEvent) => {
    event.preventDefault();
    setStatus("saving");
    setErrorMessage(null);
    try {
      const response = await fetch("/api/v1/x-api/device-profile", {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(profile),
      });
      if (!response.ok) {
        throw new Error(`HTTP ${response.status}`);
      }
      const savedProfile = (await response.json()) as DeviceProfile;
      setProfile(savedProfile);
      setReadiness((current) =>
        current === null ? current : { ...current, deviceProfileAvailable: true },
      );
      setStatus("saved");
    } catch {
      setStatus("error");
      setErrorMessage(translation.setupSaveError);
    }
  };

  const update = (field: keyof DeviceProfile, value: string) => {
    setProfile((current) => ({ ...current, [field]: value }));
    if (status === "saved") {
      setStatus("idle");
    }
  };

  const verifyConnection = async () => {
    setConnectivity("checking");
    try {
      const response = await fetch("/api/v1/x-api/connectivity/guest", { method: "POST" });
      if (!response.ok) {
        throw new Error(`HTTP ${response.status}`);
      }
      const result = (await response.json()) as {
        bearerTokenReceived: boolean;
        guestTokenReceived: boolean;
      };
      if (!result.bearerTokenReceived || !result.guestTokenReceived) {
        throw new Error("token missing");
      }
      setConnectivity("verified");
    } catch {
      setConnectivity("error");
    }
  };

  return (
    <section className="x-api-setup" data-testid="x-api-setup">
      <h3>{translation.xApiSetup}</h3>
      {status === "loading" ? (
        <div className="setup-loading">
          <LoaderCircle aria-hidden="true" className="spin" size={18} />
          <span>{translation.xApiSetup}</span>
        </div>
      ) : (
        <>
          <dl className="readiness-list">
            <ReadinessItem
              label={translation.xApiVersion}
              value={readiness?.androidApiVersion ?? "—"}
              ready={readiness !== null}
              translation={translation}
            />
            <ReadinessItem
              label={translation.clientCredentials}
              ready={readiness?.clientCredentialsAvailable ?? false}
              translation={translation}
            />
            <ReadinessItem
              label={translation.deviceProfile}
              ready={readiness?.deviceProfileAvailable ?? false}
              translation={translation}
            />
          </dl>
          <form className="device-profile-form" onSubmit={save}>
            <TextField
              label={translation.deviceModel}
              value={profile.model}
              onChange={(value) => update("model", value)}
            />
            <TextField
              label={translation.androidVersion}
              value={profile.osVersion}
              onChange={(value) => update("osVersion", value)}
            />
            <TextField
              label={translation.manufacturer}
              value={profile.manufacturer}
              onChange={(value) => update("manufacturer", value)}
            />
            <TextField
              label={translation.brand}
              value={profile.brand}
              onChange={(value) => update("brand", value)}
            />
            <TextField
              label={translation.product}
              value={profile.product}
              onChange={(value) => update("product", value)}
            />
            <label>
              <span>{translation.securityPatch}</span>
              <input
                required
                type="date"
                value={profile.securityPatchLevel}
                onChange={(event) => update("securityPatchLevel", event.target.value)}
              />
            </label>
            <button className="primary-button" type="submit" disabled={status === "saving"}>
              {status === "saving" ? translation.saving : translation.saveDeviceProfile}
            </button>
            {status === "saved" && <span className="save-success">{translation.saved}</span>}
            {errorMessage !== null && <p className="setup-error">{errorMessage}</p>}
          </form>
          <button
            className="secondary-button connectivity-button"
            type="button"
            disabled={connectivity === "checking"}
            onClick={verifyConnection}
          >
            {connectivity === "checking"
              ? translation.verifyingXConnection
              : translation.verifyXConnection}
          </button>
          {connectivity === "verified" && (
            <p className="setup-success">{translation.xConnectionVerified}</p>
          )}
          {connectivity === "error" && (
            <p className="setup-error connectivity-error">{translation.xConnectionFailed}</p>
          )}
        </>
      )}
    </section>
  );
}

interface ReadinessItemProps {
  label: string;
  value?: string;
  ready: boolean;
  translation: Translation;
}

function ReadinessItem({ label, value, ready, translation }: ReadinessItemProps) {
  const Icon = ready ? CheckCircle2 : CircleAlert;
  return (
    <div>
      <dt>{label}</dt>
      <dd className={ready ? "ready" : "not-ready"}>
        <Icon aria-hidden="true" size={16} />
        <span>{value ?? (ready ? translation.ready : translation.notReady)}</span>
      </dd>
    </div>
  );
}

interface TextFieldProps {
  label: string;
  value: string;
  onChange: (value: string) => void;
}

function TextField({ label, value, onChange }: TextFieldProps) {
  return (
    <label>
      <span>{label}</span>
      <input
        required
        maxLength={100}
        value={value}
        onChange={(event) => onChange(event.target.value)}
      />
    </label>
  );
}
