package app.benchjot.core

import org.junit.Test
import org.junit.Assert.assertEquals

class DataMatchTest {
    @Test fun findsNamesInQuotesAndPaths() {
        val names = listOf("iris.csv", "a.csv", "data_a.csv", "wine.xlsx")
        assertEquals(listOf("data_a.csv", "iris.csv"), datasetsIn("pd.read_csv('iris.csv'); open(\"data/data_a.csv\")", names))
        assertEquals(emptyList<String>(), datasetsIn("x = 'notiris.csv'", names))
        assertEquals(listOf("wine.xlsx"), datasetsIn("pd.read_excel( \"wine.xlsx\" )", names))
    }

    @Test fun namesAndOpenLines() {
        assertEquals(true, validDataName("iris.csv"))
        assertEquals(false, validDataName("../x.csv"))
        assertEquals(false, validDataName("noext"))
        assertEquals("README _1_.txt", cleanDataName("dir/README (1).txt"))
        assertEquals("pd.read_csv(\"iris.csv\")", openLine("iris.csv"))
        assertEquals("np.load(\"a.npz\")", openLine("a.npz"))
    }
}
