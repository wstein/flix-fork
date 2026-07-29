///
/// Helpers shared by the Location and Weather effects.
///
/// Named `WeatherUtil` rather than `Util` because the standard library owns the
/// top-level `Util` module.
///
mod WeatherUtil {
    use Net.Http
    use Net.HttpResponse
    use Util.Json

    ///
    /// Sends a GET request to `url` and parses the response body as JSON.
    ///
    /// Keeps the HTTP failure and the JSON failure distinct, so the caller can tell
    /// a network problem from a malformed payload.
    ///
    pub def getAndParse(url: String): Result[WeatherError, Json] \ Http =
        forM (
            response <- Http.get(url) |> Result.mapErr(WeatherError.RequestError);
            okResponse <- HttpResponse.expect(response) |> Result.mapErr(WeatherError.RequestError);
            json <- Json.parse(HttpResponse.body(okResponse)) |> Result.mapErr(WeatherError.JsonError)
        ) yield json
}
