#[derive(Debug, thiserror::Error)]
pub enum Error {
    #[error("the terminal's {role} thread could not start")]
    Thread {
        role: &'static str,
        #[source]
        source: std::io::Error,
    },
}

pub type Result<T, E = Error> = std::result::Result<T, E>;
