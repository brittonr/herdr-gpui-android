use herdr_synthetic_ssh_fixture::{Fixture, Result};
use std::io::{self, Write};
#[tokio::main]
async fn main() -> Result<()> {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let port = match args.as_slice() {
        [] => 0,
        [flag, value] if flag == "--port" => value.parse::<u16>().map_err(io::Error::other)?,
        _ => {
            return Err(io::Error::new(
                io::ErrorKind::InvalidInput,
                "usage: herdr-synthetic-ssh-fixture [--port PORT]",
            )
            .into());
        }
    };
    let fixture = Fixture::start(port).await?;
    println!("{}", serde_json::to_string(&fixture.info)?);
    io::stdout().flush()?;
    tokio::signal::ctrl_c().await?;
    fixture.shutdown().await
}
