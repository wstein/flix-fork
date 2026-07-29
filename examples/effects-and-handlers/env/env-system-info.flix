use Sys.Env

/// Reads system properties: OS name, architecture, version, and
/// the number of available virtual processors.
/// The property getters return an `Option`, since a property may be unset.
def main(): Unit \ { Env, IO } =
    let name = Env.getOsName() |> Option.getWithDefault("unknown");
    let arch = Env.getOsArch() |> Option.getWithDefault("unknown");
    let version = Env.getOsVersion() |> Option.getWithDefault("unknown");
    let cpus = Env.getVirtualProcessors();
    println("OS:   ${name}");
    println("Arch: ${arch}");
    println("Ver:  ${version}");
    println("CPUs: ${cpus}")
