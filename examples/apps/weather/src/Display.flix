use Location.Loc
use Weather.WeatherInfo

///
/// Presents a location and its weather to the user.
///
pub eff Display {
    def display(loc: Loc, weather: WeatherInfo): Unit
}

mod Display {
    use Location.Loc
    use Weather.WeatherInfo

    ///
    /// Handles display effect by printing to stdout
    ///
    pub def displayWithIO(f: Unit -> a \ ef): a \ ef - Display + IO =
        run {
            f()
        } with handler Display {
            def display(loc, weather, resume) = {
                println("Weather for ${loc}:\n\t ${weather}");
                resume()
            }
        }

}
