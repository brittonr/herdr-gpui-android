#[derive(Debug, thiserror::Error)]
pub enum Error {
    #[cfg(feature = "android-ui")]
    #[error("Could not register bundled terminal fonts")]
    FontRegistration(#[source] Box<dyn std::error::Error + Send + Sync>),
    #[cfg(feature = "android-ui")]
    #[error("Bundled font family {0} was not loaded")]
    MissingBundledFont(&'static str),
    #[error("This terminal is inactive; return to the host and open it again")]
    Inactive,
    #[error("Input belongs to an old terminal connection")]
    StaleGeneration,
    #[error("Input queue is full; wait before trying again")]
    QueueFull,
    #[error("Text must contain 1–65536 UTF-8 bytes")]
    InvalidText,
    #[error("Unsupported terminal key or key sequence")]
    InvalidKeys,
    #[error("Finish or cancel composing text before sending terminal keys")]
    Composing,
    #[error("Stale or unsupported input-method event")]
    InvalidIme,
    #[error("Unsupported terminal command")]
    InvalidCommand,
    #[error("Invalid shortcut configuration")]
    InvalidShortcuts,
    #[error("Display font size must be finite and between 11 and 36 logical pixels")]
    InvalidDisplayPreferences,
    #[error("Input JSON could not be decoded")]
    InputJson(#[from] serde_json::Error),
}

pub type Result<T> = std::result::Result<T, Error>;
