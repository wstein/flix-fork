use Location.Loc
use Weather.WeatherInfo

pub eff Weather {
    def getWeather(loc: Loc): Result[WeatherError, WeatherInfo]
}

mod Weather {
    use Location.Loc
    use Net.Http
    use Util.Json
    use Util.Json.FromJson
    use Util.Json.JsonError
    use WeatherInfoPrimitive.WeatherInfoPrimitive
    use Fs.ReadFile

    pub enum WeatherInfoPrimitive(Int32, Float64)

    pub enum WeatherInfo(String, Float64)

    instance FromJson[WeatherInfoPrimitive] {

        pub def fromJson(json: Json): Result[JsonError, WeatherInfoPrimitive] =
            forM (
                current <- FromJson.decodeAtKey("current", json);
                wmoCodeNumber <- FromJson.decodeAtKey("weather_code", current);
                temp <- FromJson.decodeAtKey("temperature_2m", current)
            ) yield WeatherInfoPrimitive.WeatherInfoPrimitive(wmoCodeNumber, temp)

    }


    instance ToString[WeatherInfo] {

        pub def toString(weatherInfo: WeatherInfo): String =
            let WeatherInfo.WeatherInfo(condition, temp) = weatherInfo;
            "${condition}, ${temp}°C"

    }

    ///
    /// Reads a json file and parses it to a `Json` value.
    /// Any failure during the process results in a `None`.
    ///
    def loadJson(f: String): Option[Json] \ ReadFile =
        forM (
            descriptions <- ReadFile.read(f) |> Result.toOption;
            json <- Json.parse(descriptions) |> Result.toOption
        ) yield json

    ///
    /// Translates a WMOCode to corresponding weather condition.
    /// We rely on data/description.json to translate.
    ///
    def parseWMOCode(n: Int32): Result[WeatherError, String] \ ReadFile =
        match loadJson("data/description.json") {
            case Option.None        => Err(WeatherError.ParseWMOCodeError("File data/description.json doesn't exist."))
            case Option.Some(descriptions) =>
                let nString = ToString.toString(n);
                let res = forM (
                    description <- FromJson.decodeAtKey(nString, descriptions);
                    day <- FromJson.decodeAtKey("day", description);
                    content <- FromJson.decodeAtKey("description", day)
                ) yield content;
                res |> Result.mapErr( err ->
                    WeatherError.JsonError(err)
                )
        }


    ///
    /// Handles Weather effect by getting the weather for the given location from open-meteo and parse it.
    ///
    pub def weatherWithHttpAndFile(f: Unit -> a \ ef): a \ ef - Weather + Http + ReadFile =
        run {
            f()
        } with handler Weather {
        def getWeather(loc, resume) =
            let Loc.Loc(_, _, lat, lon) = loc;
            let url = "https://api.open-meteo.com/v1/forecast?latitude=${lat}&longitude=${lon}&current=temperature_2m,weather_code";
            let weather = WeatherUtil.getAndParse(url) |> Result.flatMap( resJson ->
                match FromJson.fromJson(resJson) {
                    case Result.Ok(weatherInfoPrimitive)  =>
                        let WeatherInfoPrimitive(wmoCode, temp) = weatherInfoPrimitive;
                        parseWMOCode(wmoCode) |> Result.flatMap( weatherCondition ->
                            Ok(WeatherInfo.WeatherInfo(weatherCondition, temp))
                        )
                    case Result.Err(err) => Err(WeatherError.JsonError(err))
                }
            );
            resume(weather)
        }
}
